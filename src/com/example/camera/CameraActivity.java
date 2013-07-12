/*
 * Copyright (C) 2007 The Android Open Source Project
 * Copyright (C) 2012, 2013 Marcus Bauer
 * Copyright (C) 2013 Bearstech
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

package com.example.camera;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.Camera.Face;
import android.hardware.Camera.Size;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;



public class CameraActivity extends Activity {

	// ===========================================================
	// Constants
	// ===========================================================

	// Debugging
	private static final String TAG = "CAMERAACTIVITY";
	private static final boolean DEBUG = true;

	// Message types sent from the BluetoothChatService Handler
	public static final int MESSAGE_STATE_CHANGE = 1;
	public static final int MESSAGE_READ = 2;
	public static final int MESSAGE_WRITE = 3;
	public static final int MESSAGE_DEVICE_NAME = 4;
	public static final int MESSAGE_TOAST = 5;

	// Key names received from the BluetoothChatService Handler
	public static final String DEVICE_NAME = "device_name";
	public static final String TOAST = "toast";

	// Intent request codes
	private static final int REQUEST_CONNECT_DEVICE = 1;
	private static final int REQUEST_ENABLE_BT = 2;
	
	// Voice recogition
	private static final int SPEECH_REQUEST_CODE = 1234;


	// ===========================================================
	// Fields
	// ===========================================================

	// Layout Views
	private TextView tvBluetooth;
	private TextView tvCommand;
	private TextView tvArduino;

	// camera
	CameraSurfaceView mCameraSurfaceView;
	
	// bluetooth
	private String mBluetoothDeviceName = null;
	private BluetoothAdapter mBluetoothAdapter = null;
	private BluetoothService mBluetoothService = null;
	long start = 0;
	long timeLastVoiceCommand = 0;
	long timeLastCommand = 0;

	// power manager
	PowerManager pm;
	PowerManager.WakeLock wakelock;
	
	// ===========================================================
	// Methods from SuperClass/Interfaces
	// ===========================================================
	@Override
	@SuppressWarnings("deprecation")
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_camera);
		tvBluetooth = (TextView) findViewById(R.id.textView5);
		tvCommand = (TextView) findViewById(R.id.textView4);
		tvArduino = (TextView) findViewById(R.id.textView7);
		pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
		wakelock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK| PowerManager.ON_AFTER_RELEASE, TAG);
		
		//--------------
		// camera stuff
		//--------------
		mCameraSurfaceView = new CameraSurfaceView(this);
		FrameLayout frameLayout = (FrameLayout) findViewById(R.id.preview_framelayout);
		frameLayout.addView(mCameraSurfaceView);
		TextView tv = (TextView) findViewById(R.id.textView1);
		tv.bringToFront();
		
		//-----------
		// bluetooth
		//-----------
		mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
		if (mBluetoothAdapter == null) {
			Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_LONG).show();
			finish();
			return;
		}
	}
	
	@Override
	public void onStart() {
		super.onStart();
		//----------------------------
		// bluetooth - request enable
		//----------------------------
		if (!mBluetoothAdapter.isEnabled()) {
			Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
		} else if (mBluetoothService == null) {
			mBluetoothService = new BluetoothService(this, mHandler);
		}
	}
	
	@Override
	protected void onResume() {
		super.onResume();
		wakelock.acquire();
		//----------
		// bluetooth
		//----------
		if (mBluetoothService != null  && mBluetoothService.getState() == BluetoothService.STATE_NONE) {
			mBluetoothService.reset();
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		wakelock.release();
	}

	
	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
		case REQUEST_CONNECT_DEVICE:
			// When DeviceListActivity returns with a device to connect
			if (resultCode == Activity.RESULT_OK) {
				Log.d(TAG,"Menu connect request incoming, going to connectDevice()");
				String address = data.getExtras().getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
				connectDevice(address);
			}
			break;
		case REQUEST_ENABLE_BT:
			if (resultCode == Activity.RESULT_OK) {
				mBluetoothService = new BluetoothService(this, mHandler);
			} else {
				// User did not enable Bluetooth or an error occurred
				Log.d(TAG, "BT not enabled");
				Toast.makeText(this, "Bluetooth not enabled, leaving", Toast.LENGTH_SHORT).show();
				finish();
			}
		case SPEECH_REQUEST_CODE:
			if (resultCode == RESULT_OK)
			{
				ArrayList<String> matches = data
						.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);

				if (matches.size() == 0)
				{
					//tts.speak("Heard nothing", TextToSpeech.QUEUE_FLUSH, null);
				}
				else
				{
					String mostLikelyThingHeard = matches.get(0);
					//String magicWord = this.getResources().getString(R.string.magicword);
					String magicWord = "open sesame";
					if (mostLikelyThingHeard.toLowerCase().contains("light on")) {
						sendMessage("light,\n");
						Toast.makeText(this, "light", Toast.LENGTH_SHORT).show();
					}
					
					else if (mostLikelyThingHeard.toLowerCase().contains("light off")) {
						sendMessage("nolight,\n");
						Toast.makeText(this, "nolight", Toast.LENGTH_SHORT).show();
					}
					
					else if (mostLikelyThingHeard.toLowerCase().contains("play")) {
						sendMessage("play,\n");
						Toast.makeText(this, "play", Toast.LENGTH_SHORT).show();
					}
					
					else if (mostLikelyThingHeard.toLowerCase().contains("stop")) {
						sendMessage("noplay,\n");
						Toast.makeText(this, "noplay", Toast.LENGTH_SHORT).show();
					}
					
					else if (mostLikelyThingHeard.toLowerCase().contains("much light")) // too much light
						sendMessage("dimlight,\n");

					// EXTRA
					else if (mostLikelyThingHeard.toLowerCase().contains("hot tea"))
						sendMessage("relais,\n");
					
					else if (mostLikelyThingHeard.toLowerCase().contains("hot enough"))
						sendMessage("norelais,\n");

					else
						Toast.makeText(this, mostLikelyThingHeard, Toast.LENGTH_SHORT).show();

				}
			}
			else
			{
				Log.d(TAG, "result NOT ok");
			}
		}
	}
	
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.activity_camera, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle item selection
		switch (item.getItemId()) {

		default:
			return super.onOptionsItemSelected(item);
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		if (mBluetoothService != null) mBluetoothService.reset();
	}
	
	// ===========================================================
	// Methods
	// ===========================================================

	public void onBtnBTConnect(View v) {
		Log.d(TAG,"onBtnBTConnect()");
		Intent intent = new Intent(this, DeviceListActivity.class);
		startActivityForResult(intent, REQUEST_CONNECT_DEVICE);
	}
	
	public void onBtnSend(View v) {
		Log.d(TAG,"onBtnSend()");
		start = System.currentTimeMillis();
		sendMessage("foobar\n");
	}
	
	public void onBtnExtra(View v) {
		mCameraSurfaceView.camera.autoFocus(null);
	}

	private void connectDevice(String address) {
		Log.d(TAG,"connectDevice(): "+address);
		BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
		mBluetoothService.startConnectThread(device);
	}


	private void sendMessage(String message) {

		try {
			// check that there's actually something to send
			if (message.length() == 0)
				return;

			tvCommand.setText(message);
			
			// check if we are connected
			if (mBluetoothService.getState() != BluetoothService.STATE_CONNECTED) {
				return;
			}
	
			// write message
			byte[] send = message.getBytes();
			mBluetoothService.write(send);
		}
		catch (Exception e) {
			// FIXME: figure out way there is an exception when enabling bluetooth
		}
	}



	// Status bar stuff
	private final void setStatus(int resId) {
		final ActionBar actionBar = getActionBar();
		//FIXME
		//actionBar.setSubtitle(resId);
	}

	private final void setStatus(CharSequence subTitle) {
		tvBluetooth.setText(subTitle);
	}

	// Voice recognition stuff
	private void doListen(){

		long currentTime = System.currentTimeMillis();
		long timePassed = currentTime - timeLastVoiceCommand;
		
		if (timePassed < 5000)
			return;
		
		timeLastVoiceCommand = currentTime;
		
		Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
		intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
				RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
		intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say the magic word");
		intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 100);
		startActivityForResult(intent, SPEECH_REQUEST_CODE);
		
	}

	// ===========================================================
	// Inner and Anonymous Classes
	// ===========================================================


	/**
	 * A simple wrapper around a Camera and a SurfaceView that renders a centered preview of the Camera
	 * to the surface. We need to center the SurfaceView because not all devices have cameras that
	 * support preview sizes at the same aspect ratio as the device's display.
	 */
	class CameraSurfaceView extends SurfaceView implements SurfaceHolder.Callback {

		private final String TAG = "CameraView";

		SurfaceHolder mHolder;
		Size mPreviewSize;
		List<Size> mSupportedPreviewSizes;
		public Camera camera;
		Context mContext;
		Face[] mFaces = {};

		int scan = 0;
		int numFaces = 0;

		CameraSurfaceView(Context context) {
			super(context);
			mContext = context;
			mHolder = getHolder();
			mHolder.addCallback(this);
			setWillNotDraw(false);
		}

		AutoFocusCallback myAutoFocusCallback = new AutoFocusCallback(){

			@Override
			public void onAutoFocus(boolean arg0, Camera arg1) {
				// TODO Auto-generated method stub
			}
		};

		@Override
		public void surfaceCreated(SurfaceHolder holder) {
			try {
				camera = Camera.open(1);
				camera.setDisplayOrientation(90);
			} 
			catch (Exception e) {
				Toast.makeText(mContext, "Could not open Camera: "+e.getMessage(), Toast.LENGTH_LONG).show();
				//camera = null;
			}

			try {
				camera.setPreviewDisplay(holder);
				camera.startPreview();
				// start face detection only *after* preview has started
				Camera.Parameters params = camera.getParameters();

				Toast.makeText(mContext, "MAX faces: "+params.getMaxNumDetectedFaces(), Toast.LENGTH_LONG).show();
				camera.setFaceDetectionListener(new MyFaceDetectionListener());
				camera.startFaceDetection();


				camera.autoFocus(myAutoFocusCallback);

			} catch (Exception e) {
				Log.d(TAG, "Error starting CameraView: " + e.getMessage());
				Toast.makeText(mContext, "Could not start Camera Preview", Toast.LENGTH_SHORT).show();
			}
		}

		@Override
		public void surfaceDestroyed(SurfaceHolder holder) {
			try {
				camera.stopPreview();
				camera.release();
			}
			catch (Exception e) {
				Toast.makeText(mContext, "Could not stop Preview", Toast.LENGTH_SHORT).show();
			}
			camera = null;
		}

		@Override
		public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
		}


		@Override
		public void draw(Canvas canvas) {
			
			Log.d("FACEDTECT","draw()");

			super.draw(canvas); 
			scan++;

			int TEXTSIZE = 30;

			Paint paint = new Paint();

			float textWidth = 0;
			int allleft = -1000;
			int alltop = -1000;
			int allright = 1000;
			int allbottom = 1000;
			int viewWidth = getWidth();
			int viewHeight = getHeight();


			// OSD for face detection messages
			String str = "Scan: " + scan + "    Faces detected: " + numFaces;
			paint.setTextSize(TEXTSIZE);
			textWidth = paint.measureText(str);

			paint.setColor(0x77000000);
			canvas.drawRect(0, viewHeight-(TEXTSIZE+15), textWidth+40, viewHeight, paint);			

			paint.setColor(0xffffffff); 
			canvas.drawText(str, 20, viewHeight-10, paint);


			// OSD rectangles for detected faces
			paint.setStrokeWidth(20);
			paint.setStyle(Style.STROKE);
			paint.setColor(0xffffffff); 

			for(int i=0; i<mFaces.length; i++){
				/* bounds of a face: 
				 * (-1000, -1000) represents the top-left of the camera field of view, and 
				 * ( 1000,  1000) represents the bottom-right of the field of view. */
				
				// - compensate for the 90 degree rotation in portrait mode (top=left, right=bottom)
				// - compensate for mirrored front camera by changing prefix
				int faceleft   = -mFaces[i].rect.top;
				int facetop    = -mFaces[i].rect.left;
				int faceright  = -mFaces[i].rect.bottom;
				int facebottom = -mFaces[i].rect.right;
				
				// - translate coordinate system
				// - scale coordinate system
				int left   = (faceleft   + 1000) * viewWidth /2000;
				int top    = (facetop    + 1000) * viewHeight/2000;
				int right  = (faceright  + 1000) * viewWidth /2000;
				int bottom = (facebottom + 1000) * viewHeight/2000;

				// draw rectangle - because we are mirrored top=bottom and left=right
				canvas.drawRect(right, bottom, left, top, paint);

				
				allleft   = (faceleft   > allleft)   ? faceleft   : allleft;
				Log.d("FACES","alleft "+faceleft+" * "+allleft);
				alltop    = (facetop    > alltop)    ? facetop    : alltop;
				allright  = (faceright  < allright)  ? faceright  : allright;
				allbottom = (facebottom < allbottom) ? facebottom : allbottom;
			}

			if (mFaces.length == 0)
				canvas.drawColor(Color.TRANSPARENT);

			//------------------------
			// send bluetooth message
			//------------------------
			int horizontalPos = (allleft+allright)/2;
			int verticalPos  = (alltop+allbottom)/2;
			int width = allleft - allright;
			int height = allbottom - alltop;
			Log.d("FACES","width: "+width);
			
			if (System.currentTimeMillis() - timeLastCommand > 150) {
				// no face
				if(mFaces.length == 0)
					sendMessage("search\n");
				// face out of center horizontal left
				else if (horizontalPos < -300)
					sendMessage("left,"+horizontalPos+"\n");
				// face out of center horizontal right
				else if (horizontalPos > 300)
					sendMessage("right,"+horizontalPos+"\n");
				// face out of center vertical top
				else if (verticalPos < -260)
					sendMessage("up,"+verticalPos+"\n");		
				// face out of center vertical bottom
				else if (verticalPos > 260)
					sendMessage("down,"+verticalPos+"\n");				
				// face too far
				else if (width < 500) {
					sendMessage("forward,"+width+"\n");
					Log.d("FACES","forward");
				}
				// face too close
				else if (width > 750) {
					sendMessage("back,"+width+"\n");
					Log.d("FACES","back");
				}
				else
					sendMessage("okay,"+width+","+horizontalPos+"\n");
				
				timeLastCommand = System.currentTimeMillis();
			}
		}


		//----------------
		// face detection
		//----------------
		class MyFaceDetectionListener implements Camera.FaceDetectionListener {

			@Override
			public void onFaceDetection(Face[] faces, Camera camera) {
				mFaces = faces;
				numFaces = faces.length;
				invalidate();
			}
		}
	}
	
	
	//-----------------------------------------------------------------------
	// Handler to handle messages from the BluetoothService
	//-----------------------------------------------------------------------
	@SuppressLint("HandlerLeak")
	private final Handler mHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case MESSAGE_STATE_CHANGE:
				if(DEBUG) Log.i(TAG, "MESSAGE_STATE_CHANGE: " + msg.arg1);
				switch (msg.arg1) {
				case BluetoothService.STATE_CONNECTED:
					setStatus(mBluetoothDeviceName);
					break;
				case BluetoothService.STATE_CONNECTING:
					setStatus("Connecting...");
					break;
				case BluetoothService.STATE_LISTEN:
				case BluetoothService.STATE_NONE:
					setStatus("Not connected.");
					break;
				}
				break;
			case MESSAGE_WRITE:
				byte[] writeBuf = (byte[]) msg.obj;
				// construct a string from the buffer
				String writeMessage = new String(writeBuf);
				break;
			case MESSAGE_READ:
				byte[] readBuf = (byte[]) msg.obj;
				// construct a string from the valid bytes in the buffer
				String readMessage = new String(readBuf, 0, msg.arg1);
				String[] words = readMessage.split(",");
				if(words[0].equals("PROXIMITY")){
					doListen();
				}
				// FIXME put timing in other MSG
				//long ms = System.currentTimeMillis() - start;
				tvArduino.setText(readMessage);
				break;
			case MESSAGE_DEVICE_NAME:
				// save the connected device's name
				mBluetoothDeviceName = msg.getData().getString(DEVICE_NAME);
				Toast.makeText(getApplicationContext(), "Connected to "+ mBluetoothDeviceName, Toast.LENGTH_SHORT).show();
				break;
			case MESSAGE_TOAST:
				Toast.makeText(getApplicationContext(), msg.getData().getString(TOAST),
						Toast.LENGTH_SHORT).show();
				break;
			}
		}
	};
}




