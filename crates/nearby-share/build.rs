fn main() {
    #[cfg(feature = "ffi")]
    uniffi::generate_scaffolding("src/nearby_share.udl").ok();
    // Prefer procedural macros in api.rs; UDL is optional fallback.
    // When using only proc-macros, this is a no-op if the UDL file is absent.
}
