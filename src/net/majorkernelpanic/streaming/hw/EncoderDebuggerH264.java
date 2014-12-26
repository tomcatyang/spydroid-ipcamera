/*
 * Copyright (C) 2011-2014 GUIGUI Simon, fyhertz@gmail.com
 * 
 * This file is part of Spydroid (http://code.google.com/p/spydroid-ipcamera/)
 * 
 * Spydroid is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This source code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this source code; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package net.majorkernelpanic.streaming.hw;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;

import net.majorkernelpanic.streaming.hw.CodecManager.Codec;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;

/**
 * 
 * The purpose of this class is to detect and by-pass some bugs (or underspecified configuration) that
 * encoders available through the MediaCodec API may have. <br />
 * Feeding the encoder with a surface is not tested here.
 * Some bugs you may have encountered:<br />
 * <ul>
 * <li>U and V panes reversed</li>
 * <li>Some padding is needed after the Y pane</li>
 * <li>stride!=width or slice-height!=height</li>
 * </ul>
 */
@SuppressLint("NewApi")
public class EncoderDebuggerH264 {

	public final static String TAG = "EncoderDebuggerH264";
	/** Prefix that will be used for all shared preferences saved by libstreaming. */
	private static final String PREF_PREFIX = "libstreaming-";
	private final static String MIME_TYPE = "video/avc";
	private int mEncoderColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;

	private MediaCodec mEncoder;
	private int mWidth, mHeight, mSize;
	private int mFrame ,mBitRate;
	private byte[] mSPS, mPPS;
	private String mB64PPS, mB64SPS;
	private byte[] yuv420,mInitialImage; 
	private SharedPreferences mPreferences;
	byte[] m_info = null;

	public synchronized static EncoderDebuggerH264 debug(SharedPreferences prefs, int width, int height,int frame,int bitRate) {
		EncoderDebuggerH264 debugger = new EncoderDebuggerH264(prefs, width, height,frame,bitRate);
		debugger.debug();
		return debugger;
	}


	private EncoderDebuggerH264(SharedPreferences prefs, int width, int height,int frame,int bitRate) {
		mPreferences = prefs;
		mWidth = width;
		mHeight = height;
		mSize = width*height;
		mFrame = frame;
		mBitRate = bitRate;
		yuv420 = new byte[mSize*3/2];

	}
	
	private void debug() {
		//createTestImage();
		configureEncoder();
		createTestImage();
		offerEncoder();
		releaseEncoder();
	}

//	/**
//	 * Creates the test image that will be used to feed the encoder.
//	 */
//	private void createTestImage() {
//		mInitialImage = new byte[3*mSize/2];
//		for (int i=0;i<mSize;i++) {
//			mInitialImage[i] = (byte) (40+i%199);
//		}
//		for (int i=mSize;i<3*mSize/2;i+=2) {
//			mInitialImage[i] = (byte) (40+i%200);
//			mInitialImage[i+1] = (byte) (40+(i+99)%200);
//		}
//
//	}

	
	public String getB64PPS() {
		return mB64PPS;
	}

	public String getB64SPS() {
		return mB64SPS;
	}



	public int getEncoderColorFormat() {
		return mEncoderColorFormat;
	}

	/**
	 * Instantiates and starts the encoder.
	 */
	private void configureEncoder()  {
		mEncoder = MediaCodec.createEncoderByType("video/avc");
	    MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", mWidth, mHeight);
	    mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
	    mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, mFrame);
	    mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);    
	    mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); //关键帧间隔时间 单位s
	    
	    mEncoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
	    mEncoder.start();
	}

	private void releaseEncoder() {
		if (mEncoder != null) {
			try {
				mEncoder.stop();
			} catch (Exception ignore) {}
			try {
				mEncoder.release();
			} catch (Exception ignore) {}
		}
	}

	@SuppressLint("NewApi")
	private int offerEncoder() 
	{	
		Log.e("inputBuffers11", TypeConversion.byte2hex(yuv420,0,12)+":"+Base64.encodeToString(yuv420,0,12, Base64.DEFAULT));
		int pos = 0;
		swapYV12toI420(mInitialImage,yuv420,mWidth,mHeight);
	    try {
	        while (m_info == null){
	        	Log.e("outputBufferIndex","outputBufferIndex");
		        ByteBuffer[] inputBuffers = mEncoder.getInputBuffers();
		        ByteBuffer[] outputBuffers = mEncoder.getOutputBuffers();
		        int inputBufferIndex = mEncoder.dequeueInputBuffer(-1);
		        if (inputBufferIndex >= 0) 
		        {
		            ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
		            inputBuffer.clear();
		            inputBuffer.put(yuv420);
		            mEncoder.queueInputBuffer(inputBufferIndex, 0, yuv420.length, 0, 0);
		        }

		        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
		        int outputBufferIndex = mEncoder.dequeueOutputBuffer(bufferInfo,0);
		        if(outputBufferIndex<0)
		        	continue;
	            ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
	            byte[] outData = new byte[bufferInfo.size];
	            outputBuffer.get(outData);
	            //保存pps sps 只有开始时 第一个帧里有， 保存起来后面用
	            ByteBuffer spsPpsBuffer = ByteBuffer.wrap(outData);  
	            if (spsPpsBuffer.getInt() == 0x00000001){  
	            	m_info = new byte[outData.length];
	            	System.arraycopy(outData, 0, m_info, 0, outData.length);
	            	Log.e("offerEncoder", TypeConversion.byte2hex(m_info)+":"+new String(Base64.encode(m_info, Base64.DEFAULT)));
	            	int index = 4;
	            	while(spsPpsBuffer.getInt(++index) != 0x00000001){
	            		Log.e("offerEncoder", spsPpsBuffer.position()+ ":index:"+spsPpsBuffer.getInt(index));
	            		
	            	}
	            	
	            	mSPS = new byte[index-4];
	            	System.arraycopy(m_info, 4, mSPS, 0, mSPS.length);
	            	Log.e("offerEncoder", TypeConversion.byte2hex(mSPS)+":"+new String(Base64.encode(mSPS, Base64.NO_WRAP)));
	            	
	            	mPPS = new byte[m_info.length-(index+4)];
	            	System.arraycopy(m_info, index+4, mPPS, 0, mPPS.length);
	            	Log.e("offerEncoder", TypeConversion.byte2hex(mPPS)+":"+new String(Base64.encode(mPPS, Base64.NO_WRAP)));
	            	
	        		mB64SPS = Base64.encodeToString(mSPS, 0, mSPS.length, Base64.NO_WRAP);
	        		mB64PPS = Base64.encodeToString(mPPS, 0, mPPS.length, Base64.NO_WRAP);
	        		
	        		String key = PREF_PREFIX+"h264-mr-"+mFrame+","+mWidth+","+mHeight;
	        		Editor editor = mPreferences.edit();
	        		editor.putString(key, mB64SPS+","+mB64PPS);
	        		editor.commit();
	            } 
	            mEncoder.releaseOutputBuffer(outputBufferIndex, false);
	            outputBufferIndex = mEncoder.dequeueOutputBuffer(bufferInfo, 0);
	            break;
	        }
	        

	    } catch (Throwable t) {
	        t.printStackTrace();
	    }

	    return pos;
	}  
	
	
	/**
	 * Creates the test image that will be used to feed the encoder.
	 */
	private void createTestImage() {
		mInitialImage = new byte[3*mSize/2];
		for (int i=0;i<mSize;i++) {
			mInitialImage[i] = (byte) (40+i%199);
		}
		for (int i=mSize;i<3*mSize/2;i+=2) {
			mInitialImage[i] = (byte) (40+i%200);
			mInitialImage[i+1] = (byte) (40+(i+99)%200);
		}
		
		

	}
	
	 //yv12 转 yuv420p  yvu -> yuv
    public static void swapYV12toI420(byte[] yv12bytes, byte[] i420bytes, int width, int height) {      
    	System.arraycopy(yv12bytes, 0, i420bytes, 0,width*height);
    	System.arraycopy(yv12bytes, width*height+width*height/4, i420bytes, width*height,width*height/4);
    	System.arraycopy(yv12bytes, width*height, i420bytes, width*height+width*height/4,width*height/4);  
    } 
    
    private static byte[] mBuffer; 
	 //yv12 转 yuv420p  yvu -> yuv
    public synchronized static void swapYV12toI420(byte[] yv12bytes, ByteBuffer i420bytes, int width, int height) {    
    	int size = 3*width*height/2;
		// A buffer large enough for every case
		if (mBuffer==null || mBuffer.length != size) {
			mBuffer = new byte[size];
		}

		swapYV12toI420(yv12bytes,mBuffer,width,height);  
    	
    	System.arraycopy(mBuffer, 0, yv12bytes, 0, size);
    	i420bytes.put(yv12bytes);
    } 
    
	public static int getBufferSize(int width, int height) {
		return 3*width*height/2;
	}
}
