//! Worker-thread priority for the ONNX Runtime session.
//!
//! A monitoring app keeps running while the user opens another app. The
//! foreground service keeps the stream alive, but inference threads spawned by
//! ONNX Runtime inherit the caller's (normal) priority unless the session is
//! told otherwise. On Android every worker is therefore spawned as a "nice"
//! Linux thread (`nice(10)`, the same class as
//! `Process.THREAD_PRIORITY_BACKGROUND`), so the UI of whatever app the user
//! switches to always wins the CPU.
//!
//! On non-Android hosts the manager spawns plain threads. Custom thread
//! creation is supported by every ONNX Runtime build the workspace uses
//! (ORT 1.32 ships the `SessionOptionsSetCustomCreateThreadFn` API), but the
//! detector treats a rejected manager as non-fatal: inference must still run
//! if a future runtime refuses it.

use std::thread::{self, JoinHandle};

use ort::environment::ThreadManager;

/// Nice value for inference workers.
///
/// Android maps `Process.THREAD_PRIORITY_BACKGROUND` (nice 10) onto this
/// class, so the value is documented in one place and shared with the Kotlin
/// pump's thread priority.
pub const WORKER_NICE: i32 = 10;

/// Apply the background priority to the calling thread.
///
/// Returns `false` when the platform refuses the request; the thread keeps
/// running at its inherited priority. Called from inside the worker so the
/// setting can never leak to the thread that committed the session.
#[cfg(target_os = "android")]
fn set_background_priority() -> bool {
    // SAFETY: `setpriority` is a syscall wrapper. `gettid` returns the kernel
    // thread id of the caller, which is what `who` expects for a thread.
    // SAFETY: gettid has no preconditions; it returns the caller's kernel
    // thread id as an i32 that always fits the u32 id type on Android.
    let raw = unsafe { libc::gettid() };
    let tid = match libc::id_t::try_from(raw) {
        Ok(tid) => tid,
        Err(_) => return false,
    };
    let result = unsafe { libc::setpriority(libc::PRIO_PROCESS, tid, WORKER_NICE) };
    result == 0
}

#[cfg(not(target_os = "android"))]
fn set_background_priority() -> bool {
    false
}

/// Spawns ONNX Runtime workers at background priority on Android.
#[derive(Debug, Default, Clone, Copy)]
pub struct BackgroundThreadManager;

impl ThreadManager for BackgroundThreadManager {
    type Thread = JoinHandle<()>;

    fn create(&self, work: impl FnOnce() + Send + 'static) -> ort::Result<Self::Thread> {
        thread::Builder::new()
            .name("cctv-ort-worker".to_string())
            .spawn(move || {
                let raised = set_background_priority();
                if !raised {
                    // A rejected nice value must not stop inference. The worker
                    // keeps the inherited priority and the detector still runs.
                    return work();
                }
                work()
            })
            .map_err(ort::Error::wrap)
    }

    fn join(thread: Self::Thread) -> ort::Result<()> {
        thread
            .join()
            .map_err(|_| ort::Error::new("ONNX Runtime worker panicked"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::Arc;

    #[test]
    fn background_manager_runs_the_worker_closure() {
        let ran = Arc::new(AtomicBool::new(false));
        let flag = Arc::clone(&ran);
        let thread = BackgroundThreadManager
            .create(move || flag.store(true, Ordering::SeqCst))
            .expect("spawn worker");
        BackgroundThreadManager::join(thread).expect("join worker");
        assert!(ran.load(Ordering::SeqCst));
    }

    #[test]
    fn nice_value_matches_android_background_priority() {
        assert_eq!(WORKER_NICE, 10);
    }
}
