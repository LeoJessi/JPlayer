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

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLU;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.opengles.GL10;

/* package */ final class GlUtil {

    /** 齐次坐标向量大小 */
    public static final int HOMOGENEOUS_COORDINATE_VECTOR_SIZE = 4;

    /** 允许的 GL 异常 */
    public static final class GlException extends Exception {
        public GlException(String message) {
            super(message);
        }
    }

    private GlUtil() {}

    /**
     * 创建一个外部纹理（OES）
     */
    public static int createExternalTexture() {
        int[] texId = new int[1];
        GLES20.glGenTextures(1, texId, 0);
        checkGlError();
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId[0]);
        checkGlError();
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE);
        checkGlError();
        return texId[0];
    }

    /**
     * 检查 GL 错误，如果有错误则抛出异常
     */
    public static void checkGlError() {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            throw new RuntimeException("GL error: " + GLU.gluErrorString(error) + " (0x" + Integer.toHexString(error) + ")");
        }
    }

    /**
     * 获取标准化坐标边界
     */
    public static float[] getNormalizedCoordinateBounds() {
        return new float[]{
                -1, -1, 0, 1,
                 1, -1, 0, 1,
                -1,  1, 0, 1,
                 1,  1, 0, 1,
        };
    }

    /**
     * 获取纹理坐标边界
     */
    public static float[] getTextureCoordinateBounds() {
        return new float[]{
                0, 0, 0, 1,
                1, 0, 0, 1,
                0, 1, 0, 1,
                1, 1, 0, 1,
        };
    }

    /**
     * 创建 FloatBuffer
     */
    public static FloatBuffer createBuffer(float[] data) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(data.length * 4);
        byteBuffer.order(ByteOrder.nativeOrder());
        FloatBuffer floatBuffer = byteBuffer.asFloatBuffer();
        floatBuffer.put(data);
        floatBuffer.position(0);
        return floatBuffer;
    }
}
