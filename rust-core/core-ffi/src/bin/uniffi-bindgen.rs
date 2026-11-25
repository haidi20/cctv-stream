//! Host-only entry point for generating Kotlin bindings.
//!
//! The Android cdylib must not link the binding generator, so this binary is
//! gated behind the `cli` feature:
//!
//! ```text
//! cargo run -p core-ffi --features cli --bin uniffi-bindgen -- \
//!   generate --library target/aarch64-linux-android/release/libcore_ffi.so \
//!   --language kotlin --out-dir ../android/app/src/main/java
//! ```

fn main() {
    uniffi::uniffi_bindgen_main();
}
