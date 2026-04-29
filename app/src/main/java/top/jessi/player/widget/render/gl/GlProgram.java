/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package top.jessi.player.widget.render.gl;

import android.content.Context;
import android.opengl.GLES20;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import top.jessi.player.widget.render.gl.GlUtil.GlException;

/* package */ final class GlProgram {

    private static final String TAG = "GlProgram";

    private final int programHandle;
    private final Attribute[] attributes;
    private final Uniform[] uniforms;

    /**
     * 从 asset 文件路径读取 shader 源码
     */
    public GlProgram(Context context, String vertexShaderFilePath, String fragmentShaderFilePath)
            throws IOException, GlException {
        String vertexShader = loadAsset(context, vertexShaderFilePath);
        String fragmentShader = loadAsset(context, fragmentShaderFilePath);
        programHandle = GLES20.glCreateProgram();
        if (programHandle == 0) {
            throw new GlException("Unable to create program");
        }

        int vertexShaderHandle = loadShader(GLES20.GL_VERTEX_SHADER, vertexShader);
        int fragmentShaderHandle = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader);

        GLES20.glAttachShader(programHandle, vertexShaderHandle);
        GlUtil.checkGlError();
        GLES20.glAttachShader(programHandle, fragmentShaderHandle);
        GlUtil.checkGlError();
        GLES20.glLinkProgram(programHandle);
        GlUtil.checkGlError();

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(programHandle, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            String infoLog = GLES20.glGetProgramInfoLog(programHandle);
            throw new GlException("Failed to link program: " + infoLog);
        }

        GLES20.glDeleteShader(vertexShaderHandle);
        GLES20.glDeleteShader(fragmentShaderHandle);

        List<Attribute> attributeList = new ArrayList<>();
        int[] attributeCount = new int[1];
        GLES20.glGetProgramiv(programHandle, GLES20.GL_ACTIVE_ATTRIBUTES, attributeCount, 0);
        for (int i = 0; i < attributeCount[0]; i++) {
            int[] maxNameLength = new int[1];
            GLES20.glGetProgramiv(programHandle, GLES20.GL_ACTIVE_ATTRIBUTE_MAX_LENGTH, maxNameLength, 0);
            int[] length = new int[1];
            int[] size = new int[1];
            int[] type = new int[1];
            byte[] nameBytes = new byte[maxNameLength[0]];
            GLES20.glGetActiveAttrib(programHandle, i, maxNameLength[0], length, 0, size, 0, type, 0, nameBytes, 0);
            String name = new String(nameBytes, 0, length[0]);
            int location = GLES20.glGetAttribLocation(programHandle, name);
            attributeList.add(new Attribute(name, location));
        }
        attributes = attributeList.toArray(new Attribute[0]);

        List<Uniform> uniformList = new ArrayList<>();
        int[] uniformCount = new int[1];
        GLES20.glGetProgramiv(programHandle, GLES20.GL_ACTIVE_UNIFORMS, uniformCount, 0);
        for (int i = 0; i < uniformCount[0]; i++) {
            int[] maxNameLength = new int[1];
            GLES20.glGetProgramiv(programHandle, GLES20.GL_ACTIVE_UNIFORM_MAX_LENGTH, maxNameLength, 0);
            int[] length = new int[1];
            int[] size = new int[1];
            int[] type = new int[1];
            byte[] nameBytes = new byte[maxNameLength[0]];
            GLES20.glGetActiveUniform(programHandle, i, maxNameLength[0], length, 0, size, 0, type, 0, nameBytes, 0);
            String name = new String(nameBytes, 0, length[0]);
            int location = GLES20.glGetUniformLocation(programHandle, name);
            uniformList.add(new Uniform(name, location));
        }
        uniforms = uniformList.toArray(new Uniform[0]);
    }

    private static int loadShader(int type, String shaderCode) throws GlException {
        int shader = GLES20.glCreateShader(type);
        if (shader == 0) {
            throw new GlException("Unable to create shader");
        }
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String infoLog = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new GlException("Failed to compile shader: " + infoLog);
        }
        return shader;
    }

    private static String loadAsset(Context context, String filePath) throws IOException {
        StringBuilder sb = new StringBuilder();
        InputStream is = context.getAssets().open(filePath);
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }

    /**
     * 设置 buffer attribute
     */
    public void setBufferAttribute(String name, FloatBuffer buffer, int size) {
        Attribute attribute = getAttribute(name);
        GLES20.glVertexAttribPointer(attribute.location, size, GLES20.GL_FLOAT, false, 0, buffer);
        GLES20.glEnableVertexAttribArray(attribute.location);
    }

    /**
     * 设置 buffer attribute（使用 float 数组）
     */
    public void setBufferAttribute(String name, float[] data, int size) {
        setBufferAttribute(name, GlUtil.createBuffer(data), size);
    }

    /**
     * 设置 sampler texture uniform
     */
    public void setSamplerTexIdUniform(String name, int texId, int texUnitIndex) {
        int[] textures = new int[]{texId};
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + texUnitIndex);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glUniform1i(getUniform(name).location, texUnitIndex);
        GlUtil.checkGlError();
    }

    /**
     * 设置 float uniform
     */
    public void setFloatUniform(String name, float value) {
        GLES20.glUniform1f(getUniform(name).location, value);
    }

    /**
     * 设置 floats uniform（mat4）
     */
    public void setFloatsUniform(String name, float[] value) {
        GLES20.glUniformMatrix4fv(getUniform(name).location, 1, false, value, 0);
    }

    /**
     * 绑定所有 attributes 和 uniforms（在 draw 前调用）
     */
    public void bindAttributesAndUniforms() {
        GLES20.glUseProgram(programHandle);
    }

    /**
     * 删除 program
     */
    public void delete() {
        GLES20.glDeleteProgram(programHandle);
    }

    private Attribute getAttribute(String name) {
        for (Attribute attribute : attributes) {
            if (attribute.name.equals(name)) {
                return attribute;
            }
        }
        throw new IllegalStateException("Could not find attribute named " + name);
    }

    private Uniform getUniform(String name) {
        for (Uniform uniform : uniforms) {
            if (uniform.name.equals(name)) {
                return uniform;
            }
        }
        throw new IllegalStateException("Could not find uniform named " + name);
    }

    private static final class Attribute {
        public final String name;
        public final int location;

        public Attribute(String name, int location) {
            this.name = name;
            this.location = location;
        }
    }

    private static final class Uniform {
        public final String name;
        public final int location;

        public Uniform(String name, int location) {
            this.name = name;
            this.location = location;
        }
    }
}
