package com.alexmcclellan.betapt.gl;

import com.alexmcclellan.betapt.BetaPT;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.KHRDebugCallback;

import java.nio.IntBuffer;

/**
 * M0 check: what OpenGL context did Beta give us, and can it run compute shaders?
 *
 * The whole stage 1 tracer is compute shaders (OpenGL 4.3+), so this is the first
 * thing to prove. The test dispatches 64 GPU threads, each writing its index squared
 * into a buffer, then reads the buffer back and checks every value.
 */
public final class GlProbe {
    public static String glVersion = "?";
    public static String renderer = "?";
    public static boolean hasGl43;
    public static boolean hasGl45;
    public static boolean computeOk;
    public static String computeResult = "not run";

    private static final int N = 64;

    private static final String TEST_SHADER = """
            #version 430
            layout(local_size_x = 64) in;
            layout(std430, binding = 0) buffer Out { uint values[]; };
            void main() {
                uint i = gl_GlobalInvocationID.x;
                values[i] = i * i;
            }
            """;

    private GlProbe() {}

    public static void probe() {
        try {
            glVersion = GL11.glGetString(GL11.GL_VERSION);
            renderer = GL11.glGetString(GL11.GL_RENDERER);
            ContextCapabilities caps = GLContext.getCapabilities();
            hasGl43 = caps.OpenGL43;
            hasGl45 = caps.OpenGL45;
            BetaPT.LOGGER.info("OpenGL {} on {} ({})", glVersion, renderer, GL11.glGetString(GL11.GL_VENDOR));
            BetaPT.LOGGER.info("OpenGL 4.3: {}, OpenGL 4.5: {}", hasGl43, hasGl45);

            if (hasGl43) {
                installDebugOutput();
                runComputeTest();
            } else {
                computeResult = "skipped, no GL 4.3";
            }
        } catch (Throwable t) {
            computeOk = false;
            computeResult = "crashed: " + t;
            BetaPT.LOGGER.error("GL probe failed", t);
        }
        BetaPT.LOGGER.info("Compute test: {}", computeResult);
    }

    /** Route driver warnings and errors into latest.log. AMD may only send these on debug contexts. */
    private static void installDebugOutput() {
        GL11.glEnable(GL43.GL_DEBUG_OUTPUT);
        GL43.glDebugMessageCallback(new KHRDebugCallback((source, type, id, severity, message) -> {
            if (severity == GL43.GL_DEBUG_SEVERITY_HIGH || severity == GL43.GL_DEBUG_SEVERITY_MEDIUM) {
                BetaPT.LOGGER.warn("[GL debug] {}", message);
            }
        }));
    }

    private static void runComputeTest() {
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, TEST_SHADER);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            computeResult = "compile failed";
            BetaPT.LOGGER.error("Compute shader compile log:\n{}", GL20.glGetShaderInfoLog(shader, 4096));
            GL20.glDeleteShader(shader);
            return;
        }

        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, shader);
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            computeResult = "link failed";
            BetaPT.LOGGER.error("Compute program link log:\n{}", GL20.glGetProgramInfoLog(program, 4096));
            GL20.glDeleteProgram(program);
            return;
        }

        int buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, N * 4L, GL15.GL_DYNAMIC_READ);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, buffer);

        GL20.glUseProgram(program);
        GL43.glDispatchCompute(1, 1, 1);
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);

        IntBuffer result = BufferUtils.createIntBuffer(N);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, result);

        int wrong = 0;
        for (int i = 0; i < N; i++) {
            if (result.get(i) != i * i) wrong++;
        }

        // Leave GL state the way Beta expects it.
        GL20.glUseProgram(0);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, 0);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        GL15.glDeleteBuffers(buffer);
        GL20.glDeleteProgram(program);

        computeOk = wrong == 0;
        computeResult = computeOk ? "OK (" + N + "/" + N + " values correct)" : "FAILED, " + wrong + " of " + N + " wrong";
    }
}
