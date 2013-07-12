package com.example.camera;


/*
 * Copyright (C) 2009 The Android Open Source Project
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


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.UUID;


import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;


public class BluetoothService {
	// Debugging
	private static final String TAG = "BluetoothChatService";

	// Unique UUID for this application
	private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
	
	// Member fields
	private final BluetoothAdapter mAdapter;
	private final Handler mHandler;
	private ConnectThread mConnectThread;
	private CommunicationThread mCommunicationThread;
	private int mState;

	// Constants that indicate the current connection state
	public static final int STATE_NONE = 0;       // doing nothing
	public static final int STATE_LISTEN = 1;     // listening for incoming connections - unused
	public static final int STATE_CONNECTING = 2; // initiating an outgoing connection
	public static final int STATE_CONNECTED = 3;  // connected to a remote device

        // ===========================================================
        // Constructors
        // ===========================================================

	public BluetoothService(Context context, Handler handler) {
		mAdapter = BluetoothAdapter.getDefaultAdapter();
		mState = STATE_NONE;
		mHandler = handler;
	}

	
        // ===========================================================
        // Methods from SuperClass/Interfaces
        // ===========================================================
	

        // ===========================================================
        // Getter & Setter
        // ===========================================================
	
	private synchronized void setState(int state) {
		mState = state;
		mHandler.obtainMessage(CameraActivity.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
	}


	public synchronized int getState() {
		return mState;
	}
	
        // ===========================================================
        // Methods
        // ===========================================================



	public synchronized void reset() {
		// cancel any running threads
		if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}
		if (mCommunicationThread != null) {mCommunicationThread.cancel(); mCommunicationThread = null;}

		setState(STATE_NONE);
	}


	public synchronized void startConnectThread(BluetoothDevice device) {

		Log.d(TAG,"connect() called, starting ConnectThread");
		// cancel any running thread
		reset();

		// start connect thread
		mConnectThread = new ConnectThread(device);
		mConnectThread.start();
		
		setState(STATE_CONNECTING);
	}


	public synchronized void onConnected(BluetoothSocket socket, BluetoothDevice device) {

		// cancel any running thread
		reset();

		// start communication thread
		mCommunicationThread = new CommunicationThread(socket);
		mCommunicationThread.start();

		// Send the name of the connected device back to the UI Activity
		Message msg = mHandler.obtainMessage(CameraActivity.MESSAGE_DEVICE_NAME);
		Bundle bundle = new Bundle();
		bundle.putString(CameraActivity.DEVICE_NAME, device.getName());
		msg.setData(bundle);
		mHandler.sendMessage(msg);

		setState(STATE_CONNECTED);
	}



	public void write(byte[] out) {
		// create temporary object
		CommunicationThread commThread;
		// Synchronize a copy of the CommunicationThread
		synchronized (this) {
			if (mState != STATE_CONNECTED) return;
			commThread = mCommunicationThread;
		}
		// perform the write unsynchronized
		commThread.write(out);
	}


	private void connectionFailed() {
		// Send a failure message back to the Activity
		Message msg = mHandler.obtainMessage(CameraActivity.MESSAGE_TOAST);
		Bundle bundle = new Bundle();
		bundle.putString(CameraActivity.TOAST, "Unable to connect device");
		msg.setData(bundle);
		mHandler.sendMessage(msg);

		// Start the service over to restart listening mode
		BluetoothService.this.reset();
	}


	private void connectionLost() {
		// send message back to Activity
		Message msg = mHandler.obtainMessage(CameraActivity.MESSAGE_TOAST);
		Bundle bundle = new Bundle();
		bundle.putString(CameraActivity.TOAST, "Device connection was lost");
		msg.setData(bundle);
		mHandler.sendMessage(msg);

		// reset service
		BluetoothService.this.reset();
	}


        // ===========================================================
        // Inner and Anonymous Classes
        // ===========================================================
	
	//-----------------------------
	// thread to set up connection
	//-----------------------------
	private class ConnectThread extends Thread {
		private final BluetoothSocket mmSocket;
		private final BluetoothDevice mmDevice;

		//
		// constructor - get socket
		//
		public ConnectThread(BluetoothDevice device) {
			mmDevice = device;
			BluetoothSocket tmp = null;

			// Get a BluetoothSocket for a connection with the
			// given BluetoothDevice
			try {
				//tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
				tmp = device.createInsecureRfcommSocketToServiceRecord(MY_UUID);
			} catch (IOException e) {
				Log.e(TAG, "Socket create() failed", e);
			}
			mmSocket = tmp;
		}

		//
		// connect socket
		//
		public void run() {
			Log.i(TAG, "BEGIN mConnectThread run()");

			// cancel discovery because it slows down the connection
			mAdapter.cancelDiscovery();

			// connect the bluetooth socket
			try {
				mmSocket.connect();
			} catch (IOException e) {
				Log.e(TAG,"ERROR socket.connect(): " + e);
				try {
					mmSocket.close();
				} catch (IOException e2) {
					Log.e(TAG, "unable to close() socket during connection failure", e2);
				}
				connectionFailed();
				return;
			}

			// reset the this thread because we're done
			synchronized (BluetoothService.this) {
				mConnectThread = null;
			}

			// start the communication thread
			onConnected(mmSocket, mmDevice);
		}

		
		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "ConnectThread.cancel(): close() of connected socket failed", e);
			}
		}
	}

	//---------------------------------------------------------
	// Thread to handle the communication with a remote device
	//---------------------------------------------------------
	private class CommunicationThread extends Thread {
		private final BluetoothSocket mmSocket;
		private final InputStream mmInStream;
		private final OutputStream mmOutStream;
		private final BufferedReader in;

		//
		// constructor - get streams
		//
		public CommunicationThread(BluetoothSocket socket) {
			Log.d(TAG, "created CommunicationThread");
			mmSocket = socket;
			InputStream tmpIn = null;
			OutputStream tmpOut = null;

			try {
				tmpIn  = socket.getInputStream();
				tmpOut = socket.getOutputStream();
			} catch (IOException e) {
				Log.e(TAG, "temp sockets not created", e);
			}

			mmInStream = tmpIn;
			mmOutStream = tmpOut;
			
			in = new BufferedReader(new InputStreamReader(mmInStream));
		}

		//
		// read from InputStream
		//
		public void run() {
			Log.i(TAG, "BEGIN mConnectedThread");
			String line;
			
			try {
				while ((line = in.readLine()) != null) {
					// Send the obtained bytes to the UI Activity
					mHandler.obtainMessage(CameraActivity.MESSAGE_READ, line.length(), -1, line.getBytes()).sendToTarget();
				}
			} catch (IOException e) {
				Log.e(TAG,"ERROR on socket read: "+e.toString());
				connectionLost();
			}
			
		}

		//
		// write to output stream
		//
		public void write(byte[] buffer) {
			Log.d(TAG,"write()");
			try {
				mmOutStream.write(buffer,0,buffer.length);
				mHandler.obtainMessage(CameraActivity.MESSAGE_WRITE, -1, -1, buffer).sendToTarget();
			} catch (IOException e) {
				Log.e(TAG, "Exception during write(): "+ e.toString());
			}
		}

		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "close() of connect socket failed", e);
			}
		}
	}
}

