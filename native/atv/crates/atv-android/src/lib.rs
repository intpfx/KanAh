//! JNI bindings for Android TV: runs AtvServer and dispatches callbacks into Kotlin/Java.

use std::net::Ipv4Addr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, JNI_FALSE, JNI_TRUE};
use jni::{JNIEnv, JavaVM};
use once_cell::sync::Lazy;
use tokio::runtime::Runtime;
use tracing::{error, info, warn};

use atv_core::pairing::PairingStore;
use std::path::Path;

use atv_core::{AtvConfig, AtvDelegate, AtvServer, EventKind, TouchPhase};

struct JniDelegate {
    jvm: JavaVM,
    callback: GlobalRef,
}

impl JniDelegate {
    fn new(jvm: JavaVM, callback: GlobalRef) -> Self {
        Self { jvm, callback }
    }

    fn call_event(&self, kind: &str, detail: &str) {
        if let Ok(mut env) = self.jvm.attach_current_thread() {
            if let (Ok(kind), Ok(detail)) = (env.new_string(kind), env.new_string(detail)) {
                let _ = env.call_method(
                    self.callback.as_obj(),
                    "onEvent",
                    "(Ljava/lang/String;Ljava/lang/String;)V",
                    &[JValue::Object(&kind), JValue::Object(&detail)],
                );
            }
        }
    }

    fn call_void_string(&self, method: &str, arg: &str) {
        if let Ok(mut env) = self.jvm.attach_current_thread() {
            let jstr = match env.new_string(arg) {
                Ok(s) => s,
                Err(e) => {
                    warn!("failed to create JString: {e}");
                    return;
                }
            };
            let obj = self.callback.as_obj();
            let _ = env.call_method(
                obj,
                method,
                "(Ljava/lang/String;)V",
                &[JValue::Object(&jstr)],
            );
        }
    }
}

impl AtvDelegate for JniDelegate {
    fn get_audio_state(&self) -> Option<(f64, bool)> {
        let mut env = self.jvm.attach_current_thread().ok()?;
        let volume = env
            .call_method(self.callback.as_obj(), "getAudioVolume", "()D", &[])
            .ok()?
            .d()
            .ok()?;
        let muted = env
            .call_method(self.callback.as_obj(), "isAudioMuted", "()Z", &[])
            .ok()?
            .z()
            .ok()?;
        if volume.is_finite() {
            Some((volume.clamp(0.0, 1.0), muted))
        } else {
            None
        }
    }
    fn on_button(&self, name: &str) {
        info!("[Android JNI] on_button: {name}");
        self.call_void_string("onButton", name);
    }

    fn on_touch(&self, dx: f64, dy: f64, phase: TouchPhase) {
        if let Ok(mut env) = self.jvm.attach_current_thread() {
            let phase_int = match phase {
                TouchPhase::Began => 1,
                TouchPhase::Moved => 2,
                TouchPhase::Ended => 4,
            };
            let obj = self.callback.as_obj();
            let _ = env.call_method(
                obj,
                "onTouch",
                "(DDI)V",
                &[
                    JValue::Double(dx),
                    JValue::Double(dy),
                    JValue::Int(phase_int),
                ],
            );
        }
    }

    fn on_audio(&self, volume: f64, muted: bool) {
        if let Ok(mut env) = self.jvm.attach_current_thread() {
            let obj = self.callback.as_obj();
            let _ = env.call_method(
                obj,
                "onAudio",
                "(DZ)V",
                &[JValue::Double(volume), JValue::Bool(muted as u8)],
            );
        }
    }

    fn on_event(&self, kind: EventKind, detail: &str) {
        if let Ok(mut env) = self.jvm.attach_current_thread() {
            let kind_str = match env.new_string(format!("{kind:?}")) {
                Ok(s) => s,
                Err(_) => return,
            };
            let detail_str = match env.new_string(detail) {
                Ok(s) => s,
                Err(_) => return,
            };
            let obj = self.callback.as_obj();
            let _ = env.call_method(
                obj,
                "onEvent",
                "(Ljava/lang/String;Ljava/lang/String;)V",
                &[JValue::Object(&kind_str), JValue::Object(&detail_str)],
            );
        }
    }
}

struct RunningServer {
    runtime: Runtime,
    server: Option<AtvServer>,
    pairing: Arc<PairingStore>,
    identity_dir: String,
}

static SERVER_STATE: Lazy<Mutex<Option<RunningServer>>> = Lazy::new(|| Mutex::new(None));
static IS_RUNNING: AtomicBool = AtomicBool::new(false);

#[no_mangle]
pub extern "system" fn Java_com_live_tv_remote_AtvNative_nativeStartServer(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    pin: jint,
    device_id: JString,
    server_id: JString,
    local_ip: JString,
    identity_dir: JString,
    callback: JObject,
) -> jboolean {
    let mut state_guard = match SERVER_STATE.lock() {
        Ok(guard) => guard,
        Err(_) => return JNI_FALSE,
    };

    if state_guard.is_some() {
        warn!("server is already running");
        return JNI_TRUE;
    }

    if !(0..=9999).contains(&pin) {
        return JNI_FALSE;
    }
    let identity_path: String = match env.get_string(&identity_dir) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let pairing = match PairingStore::load(Path::new(&identity_path)) {
        Ok(v) => Arc::new(v),
        Err(e) => {
            error!("private identity load failed: {e}");
            return JNI_FALSE;
        }
    };

    let device_name: String = match env.get_string(&name) {
        Ok(s) => s.into(),
        Err(_) => "Android TV".to_string(),
    };

    let device_id_str: Option<String> = env
        .get_string(&device_id)
        .ok()
        .map(|s| s.into())
        .filter(|s: &String| !s.is_empty());

    let server_id_str: Option<String> = env
        .get_string(&server_id)
        .ok()
        .map(|s| s.into())
        .filter(|s: &String| !s.is_empty());

    let local_ip_str: Option<String> = env
        .get_string(&local_ip)
        .ok()
        .map(|s| s.into())
        .filter(|s: &String| !s.is_empty());

    let jvm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => {
            error!("failed to get JavaVM: {e}");
            return JNI_FALSE;
        }
    };

    let global_callback = match env.new_global_ref(callback) {
        Ok(g) => g,
        Err(e) => {
            error!("failed to create GlobalRef for callback: {e}");
            return JNI_FALSE;
        }
    };

    let runtime = match Runtime::new() {
        Ok(rt) => rt,
        Err(e) => {
            error!("failed to create Tokio runtime: {e}");
            return JNI_FALSE;
        }
    };

    let delegate = Arc::new(JniDelegate::new(jvm, global_callback));
    let mut config = AtvConfig::default();
    config.name = device_name;
    config.ui_port = None;
    config.private_key = Some(pairing.seed());
    config.pairing = Some(pairing.clone());
    config.pin = pin as u32;
    config.device_id = device_id_str;
    config.server_identifier = server_id_str;
    if let Some(ref s) = local_ip_str {
        if let Ok(ip) = s.parse::<Ipv4Addr>() {
            info!("using explicit local IP from Android: {ip}");
            config.ip = Some(ip);
        }
    }

    let server_res = runtime.block_on(async { AtvServer::start(config, delegate.clone()).await });

    match server_res {
        Ok(server) => {
            *state_guard = Some(RunningServer {
                runtime,
                server: Some(server),
                pairing,
                identity_dir: identity_path,
            });
            IS_RUNNING.store(true, Ordering::SeqCst);
            delegate.call_event("listening", "");
            info!("AtvServer successfully started on Android TV");
            JNI_TRUE
        }
        Err(e) => {
            delegate.call_event("error", &format!("failed to start receiver: {e}"));
            error!("failed to start AtvServer: {e}");
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_live_tv_remote_AtvNative_nativeStopServer(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut state_guard = match SERVER_STATE.lock() {
        Ok(guard) => guard,
        Err(_) => return,
    };

    if let Some(mut running) = state_guard.take() {
        if let Some(server) = running.server.take() {
            running.runtime.block_on(async {
                server.stop().await;
            });
        }
        IS_RUNNING.store(false, Ordering::SeqCst);
        info!("AtvServer stopped on Android TV");
    }
}

#[no_mangle]
pub extern "system" fn Java_com_live_tv_remote_AtvNative_nativeIsRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if IS_RUNNING.load(Ordering::SeqCst) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Only the local settings UI may explicitly open a short pairing window.
#[no_mangle]
pub extern "system" fn Java_com_live_tv_remote_AtvNative_nativeOpenPairingWindow(
    _env: JNIEnv,
    _class: JClass,
    seconds: jint,
) -> jboolean {
    if !(1..=300).contains(&seconds) {
        return JNI_FALSE;
    }
    if let Ok(state) = SERVER_STATE.lock() {
        if let Some(running) = state.as_ref() {
            running.pairing.open_window(seconds as u32);
            return JNI_TRUE;
        }
    }
    JNI_FALSE
}
#[no_mangle]
pub extern "system" fn Java_com_live_tv_remote_AtvNative_nativeClosePairingWindow(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Ok(state) = SERVER_STATE.lock() {
        if let Some(running) = state.as_ref() {
            running.pairing.close_window();
        }
    }
}
/// Caller stops and restarts the receiver after this operation to disconnect active sessions.
#[no_mangle]
pub extern "system" fn Java_com_live_tv_remote_AtvNative_nativeForgetControllers(
    mut env: JNIEnv,
    _class: JClass,
    identity_dir: JString,
) -> jboolean {
    let path: String = match env.get_string(&identity_dir) {
        Ok(value) => value.into(),
        Err(_) => return JNI_FALSE,
    };
    if let Ok(state) = SERVER_STATE.lock() {
        if let Some(running) = state.as_ref() {
            if path != running.identity_dir {
                return JNI_FALSE;
            }
            return if running.pairing.forget().is_ok() {
                JNI_TRUE
            } else {
                JNI_FALSE
            };
        }
        return if PairingStore::load(Path::new(&path))
            .and_then(|store| store.forget())
            .is_ok()
        {
            JNI_TRUE
        } else {
            JNI_FALSE
        };
    }
    JNI_FALSE
}
