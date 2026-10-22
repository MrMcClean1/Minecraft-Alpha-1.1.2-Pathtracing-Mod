package com.alexmcclellan.betapt.render;

import com.alexmcclellan.betapt.BetaPT;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Loads a compute shader from the mod jar and links it into a program. */
public final class ShaderUtil {
    private ShaderUtil() {}

    /**
     * Pastes in each '#include "file"' line (from the same shaders folder), the way the Vulkan
     * shader compiler does, so both tracers can share code. A file is pasted in once at most.
     */
    static String includes(String source) throws IOException { return includes(source, new java.util.HashSet<>()); }
    private static String includes(String source, java.util.Set<String> done) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : source.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#include \"") && t.endsWith("\"")) {
                String file = t.substring(10, t.length() - 1);
                if (done.add(file)) {
                    try (InputStream in = ShaderUtil.class.getResourceAsStream("/assets/betapt/shaders/" + file)) {
                        if (in == null) throw new IOException("missing shader include " + file);
                        out.append(includes(new String(in.readAllBytes(), StandardCharsets.UTF_8), done));
                    }
                }
                out.append('\n');
            } else if (!t.startsWith("#extension GL_GOOGLE_include_directive")) out.append(line).append('\n');
        }
        return out.toString();
    }

    /** Returns a program id, or 0 if compiling or linking failed (errors go to latest.log). */
    public static int loadCompute(String name) {
        String source;
        try (InputStream in = ShaderUtil.class.getResourceAsStream("/assets/betapt/shaders/" + name)) {
            if (in == null) throw new IOException("missing shader " + name);
            source = includes(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            BetaPT.LOGGER.error("Could not read shader {}", name, e);
            return 0;
        }

        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            BetaPT.LOGGER.error("Shader {} failed to compile:\n{}", name, GL20.glGetShaderInfoLog(shader, 8192));
            GL20.glDeleteShader(shader);
            return 0;
        }
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, shader);
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            BetaPT.LOGGER.error("Shader {} failed to link:\n{}", name, GL20.glGetProgramInfoLog(program, 8192));
            GL20.glDeleteProgram(program);
            return 0;
        }
        BetaPT.LOGGER.info("Loaded shader {}", name);
        return program;
    }

    /** A vertex + fragment shader pair (for drawing to the screen). Returns 0 on failure. */
    public static int loadGraphics(String vertName, String fragName) {
        int program = GL20.glCreateProgram();
        for (String name : new String[]{vertName, fragName}) {
            String source;
            try (InputStream in = ShaderUtil.class.getResourceAsStream("/assets/betapt/shaders/" + name)) {
                if (in == null) throw new IOException("missing shader " + name);
                source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                BetaPT.LOGGER.error("Could not read shader {}", name, e);
                return 0;
            }
            int shader = GL20.glCreateShader(name.endsWith(".vert") ? GL20.GL_VERTEX_SHADER : GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                BetaPT.LOGGER.error("Shader {} failed to compile:\n{}", name, GL20.glGetShaderInfoLog(shader, 8192));
                return 0;
            }
            GL20.glAttachShader(program, shader);
            GL20.glDeleteShader(shader);
        }
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            BetaPT.LOGGER.error("Shaders {} + {} failed to link:\n{}", vertName, fragName, GL20.glGetProgramInfoLog(program, 8192));
            return 0;
        }
        BetaPT.LOGGER.info("Loaded shaders {} + {}", vertName, fragName);
        return program;
    }
}
