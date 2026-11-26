//! `GET /v1/ws` (`docs/server-api.md` section 7).
//!
//! The token is read from the `Authorization` header, never the query string,
//! because query strings end up in access logs. `resume_from` replays the ring
//! buffer; when the requested id is already gone the client gets a `resync`
//! message and must reload state over REST.

use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{Query, State};
use axum::response::IntoResponse;
use serde::Deserialize;

use crate::auth::DeviceAuth;
use crate::model::{now_utc, WsMessage};
use crate::state::AppState;

/// Query parameters accepted by the socket.
#[derive(Debug, Deserialize)]
pub struct WsQuery {
    /// Last event id the client already saw.
    resume_from: Option<i64>,
}

/// Upgrade the connection and stream messages.
pub async fn ws(
    State(state): State<AppState>,
    _auth: DeviceAuth,
    Query(query): Query<WsQuery>,
    upgrade: WebSocketUpgrade,
) -> impl IntoResponse {
    upgrade.on_upgrade(move |socket| handle_socket(socket, state, query.resume_from))
}

async fn handle_socket(mut socket: WebSocket, state: AppState, resume_from: Option<i64>) {
    let mut receiver = state.broadcaster.subscribe();

    if let Some(resume_from) = resume_from {
        match state.replay_since(resume_from) {
            Some(messages) => {
                for message in messages {
                    if send(&mut socket, &message).await.is_err() {
                        return;
                    }
                }
            }
            None => {
                let resync = WsMessage::Resync {
                    event_id: 0,
                    server_time: now_utc(),
                };
                if send(&mut socket, &resync).await.is_err() {
                    return;
                }
            }
        }
    }

    loop {
        tokio::select! {
            received = receiver.recv() => {
                match received {
                    Ok(buffered) => {
                        if send(&mut socket, &buffered.message).await.is_err() {
                            return;
                        }
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {
                        let resync = WsMessage::Resync {
                            event_id: 0,
                            server_time: now_utc(),
                        };
                        if send(&mut socket, &resync).await.is_err() {
                            return;
                        }
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => return,
                }
            }
            incoming = socket.recv() => {
                match incoming {
                    Some(Ok(Message::Close(_))) | None => return,
                    Some(Ok(Message::Ping(payload))) => {
                        if socket.send(Message::Pong(payload)).await.is_err() {
                            return;
                        }
                    }
                    Some(Err(_)) => return,
                    Some(Ok(_)) => {}
                }
            }
        }
    }
}

async fn send(socket: &mut WebSocket, message: &WsMessage) -> Result<(), ()> {
    let text = serde_json::to_string(message).map_err(|_| ())?;
    socket
        .send(Message::Text(text.into()))
        .await
        .map_err(|_| ())
}
