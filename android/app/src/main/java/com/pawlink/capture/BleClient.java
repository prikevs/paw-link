package com.pawlink.capture;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.*;
import androidx.core.content.ContextCompat;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@SuppressLint("MissingPermission")
final class BleClient {
    static UUID id(String n){return UUID.fromString("7e40"+n+"-b5a3-f393-e0a9-e50e24dcca9e");}
    static final UUID SERVICE_UUID=id("0001"), STATUS_UUID=id("0002"), IMU_UUID=id("0003"), PRED_UUID=id("0004"), VOLT_UUID=id("0005");
    static final UUID BAT_SERVICE=UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"), BAT_UUID=UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"), CCC=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    interface Listener {void onStatus(String s);void onConnected(boolean b);void onFrame(ImuFrame f,long ns);void onEvidence(String kind,String value,long ns);}
    private final Context context;private final Listener listener;private final BluetoothAdapter adapter;
    private final Handler handler=new Handler(Looper.getMainLooper());private BluetoothLeScanner scanner;private BluetoothGatt gatt;private boolean scanning,closed,busy;private int retries;
    private final ArrayDeque<Runnable> queue=new ArrayDeque<>();
    private final Runnable timeout=this::operationTimeout;
    private void operationTimeout(){queue.clear();busy=false;listener.onStatus("BLE 操作超时，请重新连接");disconnectGatt();listener.onConnected(false);}
    BleClient(Context c,Listener l){context=c.getApplicationContext();listener=l;BluetoothManager m=c.getSystemService(BluetoothManager.class);adapter=m==null?null:m.getAdapter();}
    boolean hasPermission(){return ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    void scanAndConnect(){handler.post(()->{closed=false;retries=0;scan();});}
    private void scan(){if(closed)return;if(!hasPermission()){listener.onStatus("请在设置中允许附近设备权限");return;}if(adapter==null||!adapter.isEnabled()){listener.onStatus("请打开蓝牙");return;}stopScan();disconnectGatt();listener.onConnected(false);scanner=adapter.getBluetoothLeScanner();if(scanner==null)return;scanning=true;listener.onStatus("正在寻找 PawLink-Test…");scanner.startScan(scanCallback);handler.postDelayed(scanTimeout,15000);}
    private final Runnable scanTimeout=this::scanExpired;
    private void scanExpired(){if(scanning){stopScan();listener.onStatus("未找到项圈，请开机后重试");}}
    private void stopScan(){handler.removeCallbacks(scanTimeout);if(scanning&&scanner!=null&&hasPermission())scanner.stopScan(scanCallback);scanning=false;}
    private void disconnectGatt(){handler.removeCallbacks(timeout);queue.clear();busy=false;if(gatt!=null&&hasPermission()){BluetoothGatt old=gatt;gatt=null;old.disconnect();old.close();}}
    void disconnect(){closed=true;handler.removeCallbacksAndMessages(null);stopScan();disconnectGatt();listener.onConnected(false);}
    private void next(){handler.removeCallbacks(timeout);busy=false;if(!queue.isEmpty()&&gatt!=null){busy=true;handler.postDelayed(timeout,8000);queue.remove().run();}}
    private void subscribe(BluetoothGattCharacteristic c){if(c==null)return;queue.add(()->{BluetoothGattDescriptor d=c.getDescriptor(CCC);if(d==null||!gatt.setCharacteristicNotification(c,true)){listener.onStatus("无法订阅 "+c.getUuid());next();return;}boolean ok;if(Build.VERSION.SDK_INT>=33)ok=gatt.writeDescriptor(d,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)==0;else{d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);ok=gatt.writeDescriptor(d);}if(!ok){listener.onStatus("订阅失败，请重新连接");next();}});if((c.getProperties()&BluetoothGattCharacteristic.PROPERTY_READ)!=0)queue.add(()->{if(!gatt.readCharacteristic(c))next();});}
    void shutdown(){handler.post(()->{if(gatt==null){listener.onStatus("项圈未连接");return;}BluetoothGattService service=gatt.getService(SERVICE_UUID);BluetoothGattCharacteristic c=service==null?null:service.getCharacteristic(STATUS_UUID);if(c==null)return;queue.add(()->{byte[] data="SHUTDOWN".getBytes(StandardCharsets.UTF_8);boolean ok;if(Build.VERSION.SDK_INT>=33)ok=gatt.writeCharacteristic(c,data,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==0;else{c.setValue(data);c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);ok=gatt.writeCharacteristic(c);}listener.onStatus(ok?"关机命令已发送，等待设备确认":"关机命令发送失败");if(!ok)next();});if(!busy)next();});}
    private final ScanCallback scanCallback=new ScanCallback(){@Override public void onScanResult(int type,ScanResult r){handler.post(()->{if(!scanning||!hasPermission())return;ScanRecord sr=r.getScanRecord();if(sr!=null&&("PawLink-Test".equals(sr.getDeviceName())||(sr.getServiceUuids()!=null&&sr.getServiceUuids().contains(new ParcelUuid(SERVICE_UUID))))){stopScan();listener.onStatus("连接项圈中…");gatt=r.getDevice().connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);}});}@Override public void onScanFailed(int code){handler.post(()->{scanning=false;listener.onStatus("扫描失败："+code);});}};
    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state){handler.post(()->{if(g!=gatt)return;if(state==BluetoothProfile.STATE_CONNECTED&&status==0){retries=0;listener.onConnected(true);listener.onStatus("正在读取项圈服务…");if(!g.discoverServices())listener.onStatus("服务发现失败，请重连");}else if(state==BluetoothProfile.STATE_DISCONNECTED){listener.onConnected(false);disconnectGatt();listener.onStatus(closed?"项圈已断开":"项圈断开，正在重连…");if(!closed&&retries++<3)handler.postDelayed(()->scan(),2500);}});}
        @Override public void onServicesDiscovered(BluetoothGatt g,int status){handler.post(()->{if(g!=gatt)return;BluetoothGattService s=g.getService(SERVICE_UUID);if(status!=0||s==null){listener.onStatus("未找到 PawLink 服务");return;}subscribe(s.getCharacteristic(IMU_UUID));subscribe(s.getCharacteristic(STATUS_UUID));subscribe(s.getCharacteristic(PRED_UUID));subscribe(s.getCharacteristic(VOLT_UUID));BluetoothGattService b=g.getService(BAT_SERVICE);if(b!=null)subscribe(b.getCharacteristic(BAT_UUID));next();});}
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){handler.post(()->{if(g!=gatt)return;if(status!=0)listener.onStatus("部分数据订阅失败："+status);next();});}
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){handler.post(()->{if(g==gatt){if(status!=0)listener.onStatus("设备指令未成功写入");next();}});}
        @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] v,int status){handler.post(()->{if(g!=gatt)return;if(status==0)handle(c.getUuid(),v);next();});}
        @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,int status){if(Build.VERSION.SDK_INT<33)onCharacteristicRead(g,c,c.getValue(),status);}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] v){handler.post(()->{if(g==gatt)handle(c.getUuid(),v);});}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){if(Build.VERSION.SDK_INT<33)onCharacteristicChanged(g,c,c.getValue());}
    };
    private void handle(UUID id,byte[] value){if(value==null)return;long ns=SystemClock.elapsedRealtimeNanos();if(IMU_UUID.equals(id)&&value.length==20){ByteBuffer b=ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);listener.onFrame(new ImuFrame(Integer.toUnsignedLong(b.getInt()),Integer.toUnsignedLong(b.getInt()),b.getShort()/1000f,b.getShort()/1000f,b.getShort()/1000f,b.getShort()/100f,b.getShort()/100f,b.getShort()/100f),ns);}else if(PRED_UUID.equals(id))listener.onEvidence("prediction",new String(value,StandardCharsets.UTF_8),ns);else if(STATUS_UUID.equals(id)){String v=new String(value,StandardCharsets.UTF_8);if(v.contains("SHUTTING_DOWN")){closed=true;listener.onStatus("设备已确认关机，按 Reset 可重新启动");}listener.onEvidence("status",v,ns);}else if(VOLT_UUID.equals(id)&&value.length==2)listener.onEvidence("voltage",String.valueOf(Short.toUnsignedInt(ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getShort())),ns);else if(BAT_UUID.equals(id)&&value.length==1)listener.onEvidence("battery",String.valueOf(Byte.toUnsignedInt(value[0])),ns);}
}
