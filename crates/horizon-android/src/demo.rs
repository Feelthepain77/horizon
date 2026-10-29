//! Placeholder renderer.
//!
//! This is the scaffold's proof-of-life: it boots a wgpu pipeline through the
//! exact same surface/lifecycle path the real client will use, and draws the
//! touch overlay from live [`crate::touch::TouchOverlay`] state. When
//! `horizon-client` is wired in (PORTING.md § "Replacing this module"), delete
//! this module and drive the client's frame loop from the same hooks.
//!
//! What it draws:
//! * an animated sky-gradient backdrop (cheap proof that frames advance),
//! * a crosshair quad at the center,
//! * the hotbar strip with the selected slot highlighted,
//! * every overlay control from `TouchOverlay::geometry`, reacting to real
//!   touch input, with a fake "camera yaw/pitch" that `Look`/`Move` actions
//!   wobble so you can see input flow end-to-end.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

use wgpu::{
    BlendState, Color, ColorTargetState, ColorWrites, CurrentSurfaceTexture, FragmentState,
    MultisampleState, PipelineCompilationOptions, PipelineLayoutDescriptor, PrimitiveState, Queue,
    RenderPassDescriptor, RenderPipeline, RenderPipelineDescriptor, Surface, SurfaceConfiguration,
    TextureViewDescriptor, VertexBufferLayout, VertexState, VertexStepMode,
};
use winit::window::Window;

use crate::touch::{InputAction, OverlayShape, TouchOverlay};

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct Vertex {
    /// Normalized device coordinates.
    pos: [f32; 2],
    /// Straight RGBA.
    color: [f32; 4],
}

const SHADER: &str = r#"
struct VsIn {
    @location(0) pos: vec2f,
    @location(1) color: vec4f,
}
struct VsOut {
    @builtin(position) pos: vec4f,
    @location(0) color: vec4f,
}
@vertex
fn vs(in: VsIn) -> VsOut {
    var out: VsOut;
    out.pos = vec4f(in.pos, 0.0, 1.0);
    out.color = in.color;
    return out;
}
@fragment
fn fs(in: VsOut) -> @location(0) vec4f {
    return in.color;
}
"#;

/// Upper bound on overlay vertices per frame. Circles are triangle fans with
/// `SEGMENTS` triangles; ~16 elements * (SEGMENTS*3 + extras) fits well below
/// this. If exceeded, extra shapes are dropped (debug-logged once).
const MAX_OVERLAY_VERTS: u64 = 8192;
const SEGMENTS: usize = 24;

/// Frame outcome. wgpu 30 returns `CurrentSurfaceTexture` (an enum, not a
/// `Result`) from `get_current_texture`; this folds it into two states the
/// shell cares about: "surface is gone, reconfigure" and "skip this frame".
#[derive(Debug)]
pub enum FrameError {
    /// Swapchain needs reconfiguration (outdated/lost/validation).
    SurfaceGone(&'static str),
    /// Frame skipped (timeout/occluded) — retry next frame.
    Skip(&'static str),
}

pub struct Renderer {
    surface: Surface<'static>,
    config: SurfaceConfiguration,
    device: wgpu::Device,
    queue: Queue,
    pipeline: RenderPipeline,
    overlay_buffer: wgpu::Buffer,
    start: Instant,
    pub yaw: f32,
    pub pitch: f32,
    /// Forward speed driven by the joystick, for the demo "camera".
    pub speed: f32,
    /// Strafe driven by the joystick, for the demo "camera".
    pub strafe: f32,
    frame: u64,
}

impl Renderer {
    /// Boot the stack for `window`. The window must outlive the surface;
    /// passing the `Arc<Window>` keeps it alive.
    pub fn new(
        instance: &wgpu::Instance,
        window: Arc<Window>,
    ) -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        let surface = instance.create_surface(window.clone())?;
        let gpu = crate::backend::request_gpu(instance, Some(&surface))?;
        let size = window.inner_size();
        let config = crate::backend::configure_surface(
            &surface,
            &gpu.adapter,
            &gpu.device,
            size.width,
            size.height,
        );

        let shader = gpu
            .device
            .create_shader_module(wgpu::ShaderModuleDescriptor {
                label: Some("overlay shader"),
                source: wgpu::ShaderSource::Wgsl(SHADER.into()),
            });

        let pipeline = gpu
            .device
            .create_render_pipeline(&RenderPipelineDescriptor {
                label: Some("overlay pipeline"),
                layout: Some(
                    &gpu.device
                        .create_pipeline_layout(&PipelineLayoutDescriptor {
                            label: Some("overlay layout"),
                            bind_group_layouts: &[],
                            // wgpu 30 replaced push constants with `var<immediate>`;
                            // this pipeline uses neither.
                            immediate_size: 0,
                        }),
                ),
                vertex: VertexState {
                    module: &shader,
                    entry_point: Some("vs"),
                    compilation_options: PipelineCompilationOptions::default(),
                    buffers: &[Some(VertexBufferLayout {
                        array_stride: std::mem::size_of::<Vertex>() as u64,
                        step_mode: VertexStepMode::Vertex,
                        attributes: &wgpu::vertex_attr_array![0 => Float32x2, 1 => Float32x4],
                    })],
                },
                fragment: Some(FragmentState {
                    module: &shader,
                    entry_point: Some("fs"),
                    compilation_options: PipelineCompilationOptions::default(),
                    targets: &[Some(ColorTargetState {
                        format: config.format,
                        blend: Some(BlendState::ALPHA_BLENDING),
                        write_mask: ColorWrites::ALL,
                    })],
                }),
                primitive: PrimitiveState::default(),
                depth_stencil: None,
                multisample: MultisampleState::default(),
                multiview_mask: None,
                cache: None,
            });

        // Pre-allocated vertex budget; overlay geometry is streamed into it
        // every frame with `write_buffer`.
        let overlay_buffer = device_create_vertex_buffer(&gpu.device, MAX_OVERLAY_VERTS);

        Ok(Self {
            surface,
            config,
            device: gpu.device,
            queue: gpu.queue,
            pipeline,
            overlay_buffer,
            start: Instant::now(),
            yaw: 0.0,
            pitch: 0.0,
            speed: 0.0,
            strafe: 0.0,
            frame: 0,
        })
    }

    pub fn resize(&mut self, width: u32, height: u32) {
        if width > 0 && height > 0 {
            self.config.width = width;
            self.config.height = height;
            self.surface.configure(&self.device, &self.config);
        }
    }

    /// Consume overlay input for the demo camera.
    pub fn input(&mut self, action: InputAction) {
        match action {
            InputAction::Look { dx, dy } => {
                self.yaw += dx * 0.01;
                self.pitch = (self.pitch + dy * 0.01).clamp(-1.2, 1.2);
            }
            InputAction::Move { x, y } => {
                self.speed = y;
                self.strafe = x;
            }
            InputAction::ZoomBegin => log::info!("zoom begin (wire to horizon-client zoom)"),
            _ => {}
        }
    }

    pub fn render(&mut self, overlay: &TouchOverlay) -> Result<(), FrameError> {
        let t = self.start.elapsed().as_secs_f32();
        self.frame += 1;

        // ---- Build vertex list (logical px -> NDC) --------------------------------
        let (lw, lh) = logical_size(self.config.width, self.config.height);
        let mut verts: Vec<Vertex> = Vec::with_capacity(1024);

        // Animated sky gradient. The two hues drift with "yaw" plus a slow
        // time pulse so idle frames visibly advance too.
        let sky_shift = (self.yaw * 0.15).cos() * 0.06 + (t * 0.05).sin() * 0.02;
        let top = [0.28 + sky_shift, 0.48, 0.85, 1.0];
        let bottom = [0.75, 0.85, 0.95, 1.0];
        push_quad(
            &mut verts,
            [-1.0, -1.0],
            [1.0, 1.0],
            top,
            top,
            bottom,
            bottom,
        );
        // Sun band that slides with yaw + strafe — a stand-in for "the world reacts".
        let sun_x = (((self.yaw * 0.5 + self.strafe * 0.3).sin() * 0.5) + 0.5) * 2.0 - 1.0;
        let (sx0, sx1) = (sun_x - 0.04, sun_x + 0.04);
        let sun = [1.0, 0.92, 0.55, 0.9];
        push_quad(
            &mut verts,
            [sx0, 0.30 + self.pitch * 0.1],
            [sx1, 0.42 + self.pitch * 0.1],
            sun,
            sun,
            sun,
            sun,
        );

        // Crosshair.
        let ch = [0.0, 0.0, 0.0, 0.65];
        push_quad(&mut verts, [-0.012, -0.002], [0.012, 0.002], ch, ch, ch, ch);
        push_quad(&mut verts, [-0.002, -0.018], [0.002, 0.018], ch, ch, ch, ch);

        // Hotbar strip.
        let (bar, selected) = overlay.hotbar_geometry();
        let slot_w_ndc = 2.0 * (bar.w / lw) / 9.0;
        let x0 = (bar.x / lw) * 2.0 - 1.0;
        let y0 = 1.0 - (bar.y / lh) * 2.0;
        let y1 = 1.0 - ((bar.y + bar.h) / lh) * 2.0;
        for i in 0..9u8 {
            let bx0 = x0 + slot_w_ndc * i as f32 + 0.002;
            let bx1 = bx0 + slot_w_ndc - 0.004;
            let col = if i == selected {
                [1.0, 0.85, 0.3, 0.95]
            } else {
                [0.1, 0.1, 0.12, 0.55]
            };
            push_quad(&mut verts, [bx0, y1], [bx1, y0], col, col, col, col);
        }

        // Overlay controls.
        for shape in overlay.geometry() {
            push_circle(&mut verts, shape, lw, lh);
        }

        // ---- Upload + draw --------------------------------------------------------
        static BUDGET_WARNED: AtomicBool = AtomicBool::new(false);
        let bytes = bytemuck::cast_slice(&verts);
        let needed = (verts.len() as u64) * (std::mem::size_of::<Vertex>() as u64);
        if needed <= self.overlay_buffer.size() {
            self.queue.write_buffer(&self.overlay_buffer, 0, bytes);
        } else if !BUDGET_WARNED.swap(true, Ordering::Relaxed) {
            log::warn!(
                "overlay vertex budget exceeded ({} verts), drawing partial overlay",
                verts.len()
            );
        }

        let frame = match self.surface.get_current_texture() {
            CurrentSurfaceTexture::Success(f) | CurrentSurfaceTexture::Suboptimal(f) => f,
            CurrentSurfaceTexture::Timeout => return Err(FrameError::Skip("timeout")),
            CurrentSurfaceTexture::Occluded => return Err(FrameError::Skip("occluded")),
            CurrentSurfaceTexture::Outdated => return Err(FrameError::SurfaceGone("outdated")),
            CurrentSurfaceTexture::Lost => return Err(FrameError::SurfaceGone("lost")),
            CurrentSurfaceTexture::Validation => {
                log::error!("surface validation error — reconfiguring");
                return Err(FrameError::SurfaceGone("validation"));
            }
        };
        let view = frame.texture.create_view(&TextureViewDescriptor::default());

        let mut enc = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                label: Some("overlay encoder"),
            });
        {
            let clear = Color {
                r: 0.02,
                g: 0.03,
                b: 0.05,
                a: 1.0,
            };
            let mut pass = enc.begin_render_pass(&RenderPassDescriptor {
                label: Some("overlay pass"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: &view,
                    resolve_target: None,
                    ops: wgpu::Operations {
                        load: wgpu::LoadOp::Clear(clear),
                        store: wgpu::StoreOp::Store,
                    },
                    depth_slice: None,
                })],
                depth_stencil_attachment: None,
                multiview_mask: None,
                timestamp_writes: None,
                occlusion_query_set: None,
            });
            pass.set_pipeline(&self.pipeline);
            pass.set_vertex_buffer(0, self.overlay_buffer.slice(..));
            // Clip draw range to whatever fits the budget.
            let drawable =
                (self.overlay_buffer.size() / (std::mem::size_of::<Vertex>() as u64)) as u32;
            pass.draw(0..drawable.min(verts.len() as u32), 0..1);
        }
        self.queue.submit([enc.finish()]);
        // wgpu 30: presenting moved from SurfaceTexture to the queue.
        self.queue.present(frame);

        // Keep the queue drained on Android; wgpu 30 wants an explicit poll
        // when nobody else pumps it.
        let _ = self.device.poll(wgpu::PollType::Wait {
            submission_index: None,
            timeout: None,
        });

        if self.frame % 300 == 0 {
            log::debug!(
                "frame {} — yaw {:.2} pitch {:.2} speed {:.2}",
                self.frame,
                self.yaw,
                self.pitch,
                self.speed
            );
        }
        Ok(())
    }
}

fn logical_size(w: u32, h: u32) -> (f32, f32) {
    // The overlay layout is computed from physical px / scale factor, and the
    // NDC mapping only needs the ratio — physical px works directly here.
    (w as f32, h as f32)
}

fn device_create_vertex_buffer(device: &wgpu::Device, capacity_verts: u64) -> wgpu::Buffer {
    device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("overlay verts"),
        size: capacity_verts * std::mem::size_of::<Vertex>() as u64,
        usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    })
}

fn push_quad(
    v: &mut Vec<Vertex>,
    tl: [f32; 2],
    br: [f32; 2],
    c0: [f32; 4],
    c1: [f32; 4],
    c2: [f32; 4],
    c3: [f32; 4],
) {
    let [x0, y0] = tl;
    let [x1, y1] = br;
    v.extend_from_slice(&[
        Vertex {
            pos: [x0, y0],
            color: c0,
        },
        Vertex {
            pos: [x1, y0],
            color: c1,
        },
        Vertex {
            pos: [x0, y1],
            color: c2,
        },
        Vertex {
            pos: [x1, y0],
            color: c1,
        },
        Vertex {
            pos: [x1, y1],
            color: c3,
        },
        Vertex {
            pos: [x0, y1],
            color: c2,
        },
    ]);
}

fn push_circle(v: &mut Vec<Vertex>, s: OverlayShape, lw: f32, lh: f32) {
    let cx = (s.x / lw) * 2.0 - 1.0;
    let cy = 1.0 - (s.y / lh) * 2.0;
    let rx = (s.r / lw) * 2.0;
    let ry = (s.r / lh) * 2.0;
    let filled = s.color[3] > 0.0;

    if filled {
        for i in 0..SEGMENTS {
            let a0 = (i as f32) * std::f32::consts::TAU / SEGMENTS as f32;
            let a1 = ((i + 1) as f32) * std::f32::consts::TAU / SEGMENTS as f32;
            v.push(Vertex {
                pos: [cx, cy],
                color: s.color,
            });
            v.push(Vertex {
                pos: [cx + a0.cos() * rx, cy + a0.sin() * ry],
                color: dim(s.color),
            });
            v.push(Vertex {
                pos: [cx + a1.cos() * rx, cy + a1.sin() * ry],
                color: dim(s.color),
            });
        }
    } else {
        // Ring: thin annulus (a = 0 marks "ring only").
        let ring = 0.16;
        for i in 0..SEGMENTS {
            let a0 = (i as f32) * std::f32::consts::TAU / SEGMENTS as f32;
            let a1 = ((i + 1) as f32) * std::f32::consts::TAU / SEGMENTS as f32;
            let (p0, p1) = (
                [cx + a0.cos() * rx, cy + a0.sin() * ry],
                [cx + a1.cos() * rx, cy + a1.sin() * ry],
            );
            let (q0, q1) = (
                [
                    cx + a0.cos() * rx * (1.0 - ring),
                    cy + a0.sin() * ry * (1.0 - ring),
                ],
                [
                    cx + a1.cos() * rx * (1.0 - ring),
                    cy + a1.sin() * ry * (1.0 - ring),
                ],
            );
            let c = [s.color[0], s.color[1], s.color[2], 0.55];
            v.push(Vertex { pos: p0, color: c });
            v.push(Vertex { pos: p1, color: c });
            v.push(Vertex { pos: q0, color: c });
            v.push(Vertex { pos: p1, color: c });
            v.push(Vertex { pos: q1, color: c });
            v.push(Vertex { pos: q0, color: c });
        }
    }
}

fn dim(c: [f32; 4]) -> [f32; 4] {
    [c[0], c[1], c[2], (c[3] * 0.8).min(1.0)]
}
