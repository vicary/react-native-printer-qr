package com.pinmi.react.printer.adapter;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;


import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.WritableMap;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Created by xiesubin on 2017/9/20.
 */

public class USBPrinterAdapter implements PrinterAdapter{
    private static USBPrinterAdapter mInstance;


    private String LOG_TAG = "RNUSBPrinter";
    private Context mContext;
    private UsbManager mUSBManager;
    private PendingIntent mPermissionIndent;
    private UsbDevice mUsbDevice ;
    private UsbDeviceConnection mUsbDeviceConnection;
    private UsbInterface mUsbInterface;
    private UsbEndpoint mEndPoint;
    private static final String ACTION_USB_PERMISSION = "com.pinmi.react.USBPrinter.USB_PERMISSION";


    private USBPrinterAdapter(){}

    public static USBPrinterAdapter getInstance() {
        if(mInstance == null) {
            mInstance = new USBPrinterAdapter();
        }
        return mInstance;
    }

    private final BroadcastReceiver mUsbDeviceReceiver  = new BroadcastReceiver(){
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if(ACTION_USB_PERMISSION.equals(action)){
                synchronized (this) {
                    UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if(usbDevice != null){
                        if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)){
                            Log.i(LOG_TAG, "success to grant permission for device "+usbDevice.getDeviceId()+", vendor_id: "+ usbDevice.getVendorId()+ " product_id: " + usbDevice.getProductId());
                            mUsbDevice = usbDevice;
                        }else {
                            Toast.makeText(context, "用户拒绝获取USB设备权限", Toast.LENGTH_LONG).show();
                        }
                    }
                }
            } else if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)){
                if(mUsbDevice != null){
                    Toast.makeText(context, "USB设备已经被关闭", Toast.LENGTH_LONG).show();
                    closeConnectionIfExists();
                }
            }
        }
    };

    public void init(ReactApplicationContext reactContext, Callback successCallback, Callback errorCallback) {
        this.mContext = reactContext;
        this.mUSBManager = (UsbManager) this.mContext.getSystemService(Context.USB_SERVICE);
        this.mPermissionIndent = PendingIntent.getBroadcast(mContext, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        mContext.registerReceiver(mUsbDeviceReceiver, filter);
        Log.v(LOG_TAG, "RNUSBPrinter initialized");
        successCallback.invoke();
    }


    public void closeConnectionIfExists() {
        if(mUsbDeviceConnection != null) {
            mUsbDeviceConnection.releaseInterface(mUsbInterface);
            mUsbDeviceConnection.close();
            mUsbInterface = null;
            mEndPoint = null;
            mUsbDeviceConnection = null;
        }
    }

    public List<PrinterDevice> getDeviceList(Callback errorCallback) {
        if (mUSBManager == null) {
            errorCallback.invoke("USBManager is not initialized while get device list");
            return null;
        }
        List<PrinterDevice> lists = new ArrayList<>();
        for(UsbDevice usbDevice: mUSBManager.getDeviceList().values()){
            lists.add(new USBPrinterDevice(usbDevice));
        }
        return lists;
    }



    @Override
    public void selectDevice(PrinterDeviceId printerDeviceId, Callback successCallback, Callback errorCallback) {
        if (mUSBManager == null) {
            errorCallback.invoke("USBManager is not initialized before select device");
            return;
        }
        USBPrinterDeviceId usbPrinterDeviceId = (USBPrinterDeviceId) printerDeviceId;
        if(mUsbDevice != null && mUsbDevice.getVendorId() == usbPrinterDeviceId.getVendorId() && mUsbDevice.getProductId()== usbPrinterDeviceId.getProductId()) {
            Log.i(LOG_TAG, "already selected device, do not need repeat to connect");
            successCallback.invoke(new USBPrinterDevice(mUsbDevice).toRNWritableMap());
            return;
        }
        closeConnectionIfExists();
        if(mUSBManager.getDeviceList().size() == 0){
            errorCallback.invoke("Device list is empty, can not choose device");
            return;
        }
        for(UsbDevice usbDevice: mUSBManager.getDeviceList().values()){
            if(usbDevice.getVendorId() == usbPrinterDeviceId.getVendorId() && usbDevice.getProductId() == usbPrinterDeviceId.getProductId()){
                Log.v(LOG_TAG, "request for device: vendor_id: " + usbPrinterDeviceId.getVendorId() + ", product_id: " + usbPrinterDeviceId.getProductId());
                closeConnectionIfExists();
                mUSBManager.requestPermission(usbDevice, mPermissionIndent);
                successCallback.invoke(new USBPrinterDevice(usbDevice).toRNWritableMap());
                return;
            }
        }

        errorCallback.invoke("can not find specified device");
        return;
    }

    private boolean openConnection() {
        if(mUsbDevice == null){
            Log.e(LOG_TAG, "USB Deivce is not initialized");
            return false;
        }
        if(mUSBManager == null) {
            Log.e(LOG_TAG, "USB Manager is not initialized");
            return false;
        }

        if(mUsbDeviceConnection != null) {
            Log.i(LOG_TAG, "USB Connection already connected");
            return true;
        }

        UsbInterface usbInterface = mUsbDevice.getInterface(0);
        for(int i = 0; i < usbInterface.getEndpointCount(); i++){
            final UsbEndpoint ep = usbInterface.getEndpoint(i);
            if(ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if(ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                    UsbDeviceConnection usbDeviceConnection = mUSBManager.openDevice(mUsbDevice);
                    if(usbDeviceConnection == null) {
                        Log.e(LOG_TAG, "failed to open USB Connection");
                        return false;
                    }
                    if (usbDeviceConnection.claimInterface(usbInterface, true)){

                        mEndPoint = ep;
                        mUsbInterface = usbInterface;
                        mUsbDeviceConnection = usbDeviceConnection;
                        Log.i(LOG_TAG, "Device connected");
                        return true;
                    }else{
                        usbDeviceConnection.close();
                        Log.e(LOG_TAG, "failed to claim usb connection");
                        return false;
                    }
                }
            }
        }
        return true;
    }


    public void printRawData(String data, Callback errorCallback) {
        final String rawData = data;
        Log.v(LOG_TAG, "start to print raw data " + data);
        boolean isConnected = openConnection();
        if(isConnected) {
            Log.v(LOG_TAG, "Connected to device");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    byte [] bytes = Base64.decode(rawData, Base64.DEFAULT);
                    int b = mUsbDeviceConnection.bulkTransfer(mEndPoint, bytes, bytes.length, 100000);
                    Log.i(LOG_TAG, "Return Status: b-->"+b);
                }
            }).start();
        }else{
            String msg = "failed to connected to device";
            Log.v(LOG_TAG, msg);
            errorCallback.invoke(msg);
        }
    }




}
