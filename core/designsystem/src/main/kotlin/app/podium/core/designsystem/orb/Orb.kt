package app.podium.core.designsystem.orb

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the orb shows at one frame (D-83): where it is and how the music moves it. Everything is 0–1
 * unless named otherwise; the caller smooths the music before it gets here.
 */
class OrbFrame {
    /** Seconds of the orb's own slow time (its noise drifts with it). */
    var time = 0f

    /** Its turn, in radians. */
    var rotation = 0f

    /** The whole music's energy: brightness and the ring's width. */
    var energy = 0f

    /** The bass's swell: the ring breathes out a little. */
    var low = 0f

    /** The top's detail: the surface ripples a little. */
    var high = 0f

    /** A hit's light: the moving highlight catches gold. */
    var glint = 0f

    /** How much of the orb shows (fading in and out with the camera). */
    var alpha = 1f
}

/**
 * Podium's orb (D-83): a softly luminous ring around a dark hollow, ivory with a touch of champagne
 * gold, on black. Its edge drifts like something living, a highlight travels around it, and the
 * music swells, ripples and lights it a little. The visual idea follows the "voice-powered orb" the
 * listener shared (a 21st.dev component); the shader is Podium's own.
 *
 * A GPU shader (AGSL) from Android 13; before that, a calmer drawing of gradients. The shader is
 * made once per renderer and only its uniforms change per frame; it is drawn over the orb's own
 * square, never the whole display. No blur, no offscreen pass.
 */
class OrbRenderer {

    private val shader: RuntimeShader? = if (Build.VERSION.SDK_INT >= 33) makeShader() else null
    private val brush: ShaderBrush? = shader?.let { ShaderBrush(it) }

    /** Whether this renderer draws with the shader (false: the gradient drawing). */
    val shaded: Boolean get() = shader != null

    /** Draws the orb centred at [center] with outer radius [radius] (px). */
    fun draw(scope: DrawScope, center: Offset, radius: Float, frame: OrbFrame) {
        if (frame.alpha <= 0.002f || radius <= 0f) return
        val s = shader
        if (s != null && Build.VERSION.SDK_INT >= 33) drawShaded(scope, s, center, radius, frame) else drawPlain(scope, center, radius, frame)
    }

    @RequiresApi(33)
    private fun drawShaded(scope: DrawScope, s: RuntimeShader, center: Offset, radius: Float, f: OrbFrame) {
        s.setFloatUniform("center", center.x, center.y)
        s.setFloatUniform("radius", radius)
        s.setFloatUniform("time", f.time)
        s.setFloatUniform("rotation", f.rotation)
        s.setFloatUniform("energy", f.energy.coerceIn(0f, 1f))
        s.setFloatUniform("low", f.low.coerceIn(0f, 1f))
        s.setFloatUniform("high", f.high.coerceIn(0f, 1f))
        s.setFloatUniform("glint", f.glint.coerceIn(0f, 1f))
        s.setFloatUniform("alpha", f.alpha.coerceIn(0f, 1f))
        val extent = radius * 1.08f
        scope.drawRect(brush!!, topLeft = Offset(center.x - extent, center.y - extent), size = Size(extent * 2, extent * 2))
    }

    /** Before Android 13: a ring of gradients and a travelling light, moved by the same values. */
    private fun drawPlain(scope: DrawScope, center: Offset, radius: Float, f: OrbFrame) {
        val a = f.alpha.coerceIn(0f, 1f)
        val ring = radius * (0.79f + 0.025f * f.low)
        val body = (0.55f + 0.35f * f.energy) * a
        scope.drawCircle(
            Brush.radialGradient(
                0f to Color.Transparent,
                0.52f to Color.Transparent,
                0.74f to Shadow.copy(alpha = body),
                0.86f to lerp(Ivory, Gold, 0.3f).copy(alpha = body),
                0.97f to Color.Transparent,
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
        val angle = f.rotation + f.time * 0.6f
        val light = Offset(center.x + cos(angle) * ring, center.y + sin(angle) * ring)
        scope.drawCircle(
            Brush.radialGradient(
                0f to lerp(Ivory, Gold, 0.5f + 0.4f * f.glint).copy(alpha = (0.55f + 0.35f * f.glint) * a),
                1f to Color.Transparent,
                center = light,
                radius = radius * 0.22f,
            ),
            radius = radius * 0.22f,
            center = light,
        )
    }

    @RequiresApi(33)
    private fun makeShader(): RuntimeShader? = try {
        RuntimeShader(AGSL).apply {
            setFloatUniform("ivory", Ivory.red, Ivory.green, Ivory.blue)
            setFloatUniform("gold", Gold.red, Gold.green, Gold.blue)
            setFloatUniform("shadow", Shadow.red, Shadow.green, Shadow.blue)
        }
    } catch (e: RuntimeException) {
        null // a renderer without AGSL (or a test double): the gradient drawing
    }

    companion object {
        /** Podium's warm ivory, the line's own (D-81). */
        val Ivory = Color(0xFFF2EBDC)

        /** Muted champagne gold. */
        val Gold = Color(0xFFD6B677)

        /** The ring's dim inner tone: a warm, nearly black grey. */
        val Shadow = Color(0xFF1C1A17)

        /** The black the orb lives on. */
        val Black = Color(0xFF050505)

        /**
         * The orb, per pixel. Coordinates are measured from the centre in units of the outer radius.
         * The ring's radius drifts with smooth 3D value noise taken around the circle and through
         * time; its light falls off softly from the ring, the hollow inside goes dark, and a highlight
         * travels around it. Output is premultiplied.
         */
        private val AGSL = """
            uniform float2 center;
            uniform float radius;
            uniform float time;
            uniform float rotation;
            uniform float energy;
            uniform float low;
            uniform float high;
            uniform float glint;
            uniform float alpha;
            uniform float3 ivory;
            uniform float3 gold;
            uniform float3 shadow;

            // A lattice hash of Podium's own: folds the point into 0-1, scales, multiplies.
            float hash(float3 p) {
                p = fract(p * 0.1371 + float3(0.17, 0.31, 0.73));
                p *= 23.17;
                return fract(p.x * p.y * (p.z + 0.53) * 3.71 + p.z * 0.29);
            }

            float noise(float3 x) {
                float3 i = floor(x);
                float3 f = fract(x);
                f = f * f * (3.0 - 2.0 * f);
                float a = mix(hash(i), hash(i + float3(1.0, 0.0, 0.0)), f.x);
                float b = mix(hash(i + float3(0.0, 1.0, 0.0)), hash(i + float3(1.0, 1.0, 0.0)), f.x);
                float c = mix(hash(i + float3(0.0, 0.0, 1.0)), hash(i + float3(1.0, 0.0, 1.0)), f.x);
                float d = mix(hash(i + float3(0.0, 1.0, 1.0)), hash(i + float3(1.0, 1.0, 1.0)), f.x);
                return mix(mix(a, b, f.y), mix(c, d, f.y), f.z);
            }

            half4 main(float2 fragCoord) {
                float2 p = (fragCoord - center) / radius;
                float cr = cos(rotation);
                float sr = sin(rotation);
                p = float2(cr * p.x - sr * p.y, sr * p.x + cr * p.y);
                // The top's ripple: a small, slow wobble of the surface.
                float ripple = 0.010 + 0.028 * high;
                p += ripple * float2(sin(p.y * 6.0 + time * 1.3), sin(p.x * 6.0 + time * 1.1));
                float len = length(p);
                float2 dir = len > 0.0 ? p / len : float2(1.0, 0.0);
                // The ring drifts: noise around the circle and through time; the bass breathes it out.
                float n = 0.62 * noise(float3(dir * 1.35, time * 0.32)) + 0.38 * noise(float3(dir * 2.7 + 7.0, time * 0.5));
                float ring = mix(0.68, 0.84, n) * (1.0 + 0.035 * low);
                float d = len - ring;
                // The body: inside the ring its light spreads wide and soft, outside it falls off quickly.
                float spread = (d < 0.0 ? 34.0 : 420.0) / (1.0 + 0.8 * energy);
                float body = 1.0 / (1.0 + d * d * spread);
                // Light moves along the ring, slowly, so the surface never sits still.
                float along = 0.62 + 0.38 * noise(float3(dir * 3.1 + 11.0, time * 0.6));
                // A second, deeper ring drifting on its own: depth.
                float n2 = noise(float3(dir * 1.9 + 3.0, time * 0.41));
                float d2 = len - ring * mix(0.74, 0.88, n2);
                float deep = 1.0 / (1.0 + d2 * d2 * 1100.0);
                // The thin bright edge, where the light is densest.
                float edge = 1.0 / (1.0 + abs(d) * 110.0);
                // The hollow goes dark; the outside fades before the square's edge.
                float hollow = smoothstep(0.22, 0.52, len);
                float outside = 1.0 - smoothstep(0.88, 1.05, len);
                // A light travelling around the ring.
                float ang = time * 0.55 + rotation * 0.5;
                float2 lp = float2(cos(ang), sin(ang)) * ring;
                float2 dl = p - lp;
                float light = 1.0 / (1.0 + dot(dl, dl) * 48.0);
                // Ivory at the ring, warm grey toward the hollow, gold on the side the light is on.
                float side = 0.5 + 0.5 * dot(dir, float2(cos(ang), sin(ang)));
                float3 warm = mix(ivory, gold, 0.16 + 0.30 * side + 0.25 * glint);
                float3 col = mix(shadow, warm, clamp(body * along * 1.2 + edge * 0.3, 0.0, 1.0));
                float lum = body * along * (0.38 + 0.30 * energy) + deep * (0.16 + 0.10 * high) + edge * 0.12 + light * (0.24 + 0.42 * glint);
                col += gold * light * (0.22 + 0.5 * glint);
                lum = clamp(lum * hollow * outside, 0.0, 1.0) * alpha;
                return half4(half3(col * lum), half(lum));
            }
        """.trimIndent()
    }
}
