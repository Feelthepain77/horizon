//! Vulkan-only wgpu bootstrap for Android.
//!
//! Horizon's desktop renderer is Vulkan on Linux/Windows, so the port targets
//! Vulkan on Android as well (see PORTING.md § "Graphics strategy" for the
//! device-coverage discussion and the GLES fallback plan).
//!
//! Everything here is intentionally synchronous: `android_main` owns a dedicated
//! thread, and blocking on adapter/device enumeration there is both correct and
//! the simplest thing that survives Android lifecycle churn.

use std::sync::OnceLock;

use wgpu::{
    Adapter, AdapterInfo, Backends, CompositeAlphaMode, Device, DeviceDescriptor, Features,
    Instance, InstanceDescriptor, Limits, PresentMode, Queue, RequestAdapterOptions, Surface,
    SurfaceConfiguration, TextureUsages,
};

/// Adapter info string for the launcher UI (populated after first init).
static GPU_INFO: OnceLock<String> = OnceLock::new();

/// Create a Vulkan-only instance. Deliberately *not* `Backends::PRIMARY()` —
/// an explicit backend prevents wgpu from silently falling back to GL on
/// devices with broken Vulkan, which would diverge from the tested path.
pub fn create_instance() -> Instance {
    // wgpu 30's InstanceDescriptor has no `Default` (it gained backend
    // options + display handles); the constructor covers the platform field.
    let mut desc = InstanceDescriptor::new_without_display_handle();
    desc.backends = Backends::VULKAN;
    Instance::new(desc)
}

/// Everything needed to drive frames, plus adapter metadata for logging.
pub struct GpuBundle {
    pub adapter: Adapter,
    pub device: Device,
    pub queue: Queue,
    /// Kept for the client-integration log line + future feature gating
    /// decisions (PORTING.md § "Limits audit").
    #[allow(dead_code)]
    pub adapter_info: AdapterInfo,
}

/// Request adapter + device. `surface` is passed so the adapter is guaranteed
/// to be presentation-capable.
pub fn request_gpu(
    instance: &Instance,
    surface: Option<&Surface<'_>>,
) -> Result<GpuBundle, String> {
    let adapter = pollster::block_on(instance.request_adapter(&RequestAdapterOptions {
        power_preference: wgpu::PowerPreference::HighPerformance,
        compatible_surface: surface,
        force_fallback_adapter: false,
        apply_limit_buckets: false,
    }))
    .map_err(|e| format!("no Vulkan adapter found: {e}"))?;

    // NOTE (PORTING.md § "Limits audit"): this requests the conservative
    // WebGPU default limits. When wiring horizon-client, switch to the
    // client's required limits and add a mobile feature audit pass.
    let (device, queue) = pollster::block_on(adapter.request_device(&DeviceDescriptor {
        label: Some("horizon-android device"),
        required_features: Features::empty(),
        required_limits: Limits::default(),
        ..DeviceDescriptor::default()
    }))
    .map_err(|e| format!("device request failed: {e}"))?;

    let adapter_info = adapter.get_info();
    let info = format!(
        "{} ({}) — {} {} [{:?}]",
        adapter_info.name,
        match adapter_info.device_type {
            wgpu::DeviceType::DiscreteGpu => "discrete",
            wgpu::DeviceType::IntegratedGpu => "integrated",
            wgpu::DeviceType::VirtualGpu => "virtual",
            _ => "other",
        },
        adapter_info.driver,
        adapter_info.driver_info,
        adapter_info.backend,
    );
    log::info!("GPU: {info}");
    let _ = GPU_INFO.set(info.clone());

    Ok(GpuBundle {
        adapter,
        device,
        queue,
        adapter_info,
    })
}

/// Cached adapter info (or a placeholder before the game activity has booted).
/// Called from the JNI bridge (Android-gated), so it looks dead on host builds.
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
pub fn gpu_info() -> &'static str {
    GPU_INFO
        .get()
        .map(String::as_str)
        .unwrap_or("uninitialized (start the game once)")
}

/// Configure a surface for presentation. Picks the first sRGB format the
/// surface supports — Android Vulkan swapchains commonly expose
/// `Bgra8UnormSrgb`/`Rgba8UnormSrgb` — and FIFO present mode (vsync), which is
/// the only mode guaranteed by the platform.
pub fn configure_surface(
    surface: &Surface<'_>,
    adapter: &Adapter,
    device: &Device,
    width: u32,
    height: u32,
) -> SurfaceConfiguration {
    let caps = surface.get_capabilities(adapter);
    let format = caps
        .formats
        .iter()
        .copied()
        .find(|f| f.is_srgb())
        .or_else(|| caps.formats.first().copied())
        .unwrap_or(wgpu::TextureFormat::Bgra8UnormSrgb);

    let config = SurfaceConfiguration {
        usage: TextureUsages::RENDER_ATTACHMENT,
        format,
        color_space: wgpu::SurfaceColorSpace::Auto,
        width: width.max(1),
        height: height.max(1),
        present_mode: PresentMode::Fifo,
        desired_maximum_frame_latency: 2,
        alpha_mode: CompositeAlphaMode::Auto,
        view_formats: Vec::new(),
    };
    surface.configure(device, &config);
    config
}
