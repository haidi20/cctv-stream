//! Local RTSP test doubles.
//!
//! Each server listens on an ephemeral loopback port and speaks just enough of
//! RTSP 1.0 to drive `RtspSource` through DESCRIBE -> SETUP -> PLAY. Nothing
//! leaves the machine and no privileged port is used.

#![allow(dead_code)]

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

/// Item 96 is H.264; see RFC 6184.
const H264_PAYLOAD_TYPE: u8 = 96;
const SESSION_ID: &str = "0A1B2C3D";
const CHALLENGE: &str = "Digest realm=\"cctv-stream\", nonce=\"dcd98b7102dd2f0e\"";

/// SDP from a camera that advertises one H.264 video track.
///
/// The parameter sets are the Reolink pair that `retina` uses in its own tests,
/// so they parse as real SPS/PPS rather than filler.
const SDP: &str = "\
v=0\r
o=- 0 0 IN IP4 127.0.0.1\r
s=cctv-stream mock\r
t=0 0\r
a=control:*\r
m=video 0 RTP/AVP 96\r
a=rtpmap:96 H264/90000\r
a=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z2QAM6zoAoALWQ==,aO48MA==\r
a=control:trackID=1\r
";

/// What the fake camera does once the session is playing.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Script {
    /// Two access units, then stay connected so the client can keep reading.
    TwoFrames,
    /// Answer `PLAY` but never send media, so the client hits its read timeout.
    Silent,
    /// Two access units, then close the connection mid-stream.
    CloseAfterTwoFrames,
}

/// How the camera handles authentication.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Auth {
    /// Challenge once, then accept a request carrying `Authorization`.
    Digest,
    /// Challenge every request, so even valid credentials are refused.
    AlwaysReject,
    /// Never challenge; open camera on a trusted LAN.
    None,
}

/// One request the fake camera received, reduced to what tests assert on.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Request {
    /// RTSP method, e.g. `DESCRIBE`.
    pub method: String,
    /// True when the camera saw an `Authorization` header.
    pub authorized: bool,
}

/// A scripted RTSP camera on loopback.
pub struct MockRtsp {
    port: u16,
    requests: Arc<Mutex<Vec<Request>>>,
    handle: Option<JoinHandle<()>>,
}

impl MockRtsp {
    /// Open camera that streams two H.264 access units.
    pub fn streaming() -> Self {
        Self::spawn(Some(Script::TwoFrames), Auth::None)
    }

    /// Camera that demands digest auth and accepts the credentials the client
    /// extracted from the URL.
    pub fn protected() -> Self {
        Self::spawn(Some(Script::TwoFrames), Auth::Digest)
    }

    /// Camera that refuses every attempt, even with credentials.
    pub fn rejecting_everything() -> Self {
        Self::spawn(Some(Script::TwoFrames), Auth::AlwaysReject)
    }

    /// Camera that accepts the session but never sends media.
    pub fn silent() -> Self {
        Self::spawn(Some(Script::Silent), Auth::None)
    }

    /// Camera that disappears mid-stream.
    pub fn closing() -> Self {
        Self::spawn(Some(Script::CloseAfterTwoFrames), Auth::None)
    }

    /// `rtsp://127.0.0.1:<port>/stream`, without credentials.
    pub fn url(&self) -> String {
        format!("rtsp://127.0.0.1:{}/stream", self.port)
    }

    /// Same URL with credentials embedded the way the Android settings screen
    /// stores them.
    pub fn url_with_credentials(&self, user: &str, password: &str) -> String {
        format!("rtsp://{user}:{password}@127.0.0.1:{}/stream", self.port)
    }

    /// Requests the camera saw, in arrival order.
    pub fn requests(&self) -> Vec<Request> {
        self.requests
            .lock()
            .expect("request log is not poisoned")
            .clone()
    }

    fn spawn(script: Option<Script>, auth: Auth) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback");
        let port = listener.local_addr().expect("local addr").port();
        let requests = Arc::new(Mutex::new(Vec::new()));
        let recorded = Arc::clone(&requests);

        let handle = thread::spawn(move || {
            let Ok((stream, _)) = listener.accept() else {
                return;
            };
            let _ = stream.set_read_timeout(Some(Duration::from_secs(15)));
            serve(stream, script, auth, recorded);
        });

        Self {
            port,
            requests,
            handle: Some(handle),
        }
    }
}

impl Drop for MockRtsp {
    fn drop(&mut self) {
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

fn serve(
    mut stream: TcpStream,
    script: Option<Script>,
    auth: Auth,
    requests: Arc<Mutex<Vec<Request>>>,
) {
    let mut pending = Vec::new();
    let mut challenged = false;
    let mut authenticated = false;

    loop {
        let Some(request) = read_request(&mut stream, &mut pending) else {
            return;
        };
        let Some(cseq) = header(&request, "CSeq") else {
            return;
        };
        let Some(method) = request.split_whitespace().next().map(str::to_owned) else {
            return;
        };
        let authorized = header(&request, "Authorization").is_some();

        requests
            .lock()
            .expect("request log is not poisoned")
            .push(Request {
                method: method.clone(),
                authorized,
            });

        match auth {
            Auth::AlwaysReject => {
                respond(
                    &mut stream,
                    &cseq,
                    "401 Unauthorized",
                    &[("WWW-Authenticate", CHALLENGE)],
                    "",
                );
                continue;
            }
            Auth::Digest if challenged && !authenticated => {
                if !authorized {
                    respond(
                        &mut stream,
                        &cseq,
                        "401 Unauthorized",
                        &[("WWW-Authenticate", CHALLENGE)],
                        "",
                    );
                    continue;
                }
                authenticated = true;
            }
            Auth::Digest if !challenged => {
                challenged = true;
                respond(
                    &mut stream,
                    &cseq,
                    "401 Unauthorized",
                    &[("WWW-Authenticate", CHALLENGE)],
                    "",
                );
                continue;
            }
            Auth::Digest | Auth::None => {}
        }

        let Some(script) = script else {
            respond(
                &mut stream,
                &cseq,
                "404 Not Found",
                &[("Session", SESSION_ID)],
                "",
            );
            continue;
        };

        match method.as_str() {
            "DESCRIBE" => respond(
                &mut stream,
                &cseq,
                "200 OK",
                &[("Content-Type", "application/sdp")],
                SDP,
            ),
            "SETUP" => respond(
                &mut stream,
                &cseq,
                "200 OK",
                &[
                    ("Session", SESSION_ID),
                    ("Transport", "RTP/AVP/TCP;unicast;interleaved=0-1"),
                ],
                "",
            ),
            "PLAY" => {
                respond(
                    &mut stream,
                    &cseq,
                    "200 OK",
                    &[
                        ("Session", SESSION_ID),
                        ("Range", "npt=0.000-"),
                        ("RTP-Info", "url=trackID=1;seq=1;rtptime=0"),
                    ],
                    "",
                );

                match script {
                    Script::TwoFrames | Script::CloseAfterTwoFrames => {
                        send_access_unit(&mut stream, 1, 0, b"\x65\x88\x84\x21\xff\xfe");
                        send_access_unit(&mut stream, 2, 3_600, b"\x41\x9a\x22\x33\x44");
                        if script == Script::CloseAfterTwoFrames {
                            return;
                        }
                    }
                    Script::Silent => {}
                }
            }
            _ => respond(&mut stream, &cseq, "200 OK", &[("Session", SESSION_ID)], ""),
        }
    }
}

fn respond(stream: &mut TcpStream, cseq: &str, status: &str, headers: &[(&str, &str)], body: &str) {
    let mut message = format!("RTSP/1.0 {status}\r\nCSeq: {cseq}\r\n");
    for (name, value) in headers {
        message.push_str(name);
        message.push_str(": ");
        message.push_str(value);
        message.push_str("\r\n");
    }
    message.push_str(&format!("Content-Length: {}\r\n\r\n{body}", body.len()));
    let _ = stream.write_all(message.as_bytes());
}

/// Writes one interleaved RTP packet carrying a complete H.264 NAL unit.
fn send_access_unit(stream: &mut TcpStream, sequence: u16, timestamp: u32, nal: &[u8]) {
    let mut rtp = Vec::with_capacity(12 + nal.len());
    rtp.push(0x80);
    // Marker bit plus payload type: this is the end of the access unit.
    rtp.push(0x80 | H264_PAYLOAD_TYPE);
    rtp.extend_from_slice(&sequence.to_be_bytes());
    rtp.extend_from_slice(&timestamp.to_be_bytes());
    rtp.extend_from_slice(&0xDEAD_BEEFu32.to_be_bytes());
    rtp.extend_from_slice(nal);

    let mut frame = Vec::with_capacity(4 + rtp.len());
    // '$' on the RTP channel, then a 16-bit big-endian length.
    frame.push(b'$');
    frame.push(0);
    frame.extend_from_slice(
        &u16::try_from(rtp.len())
            .expect("RTP frame fits u16")
            .to_be_bytes(),
    );
    frame.extend_from_slice(&rtp);
    let _ = stream.write_all(&frame);
}

fn read_request(stream: &mut TcpStream, pending: &mut Vec<u8>) -> Option<String> {
    loop {
        if let Some(end) = pending.windows(4).position(|w| w == b"\r\n\r\n") {
            let request = String::from_utf8_lossy(&pending[..end + 4]).into_owned();
            pending.drain(..end + 4);
            return Some(request);
        }

        let mut chunk = [0u8; 1024];
        match stream.read(&mut chunk) {
            Ok(0) | Err(_) => return None,
            Ok(read) => pending.extend_from_slice(&chunk[..read]),
        }

        if pending.len() > 64 * 1024 {
            return None;
        }
    }
}

fn header(request: &str, name: &str) -> Option<String> {
    request.lines().find_map(|line| {
        let (key, value) = line.split_once(':')?;
        key.trim()
            .eq_ignore_ascii_case(name)
            .then(|| value.trim().to_owned())
    })
}
