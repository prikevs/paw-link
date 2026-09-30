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
    static final UUID SERVICE_UUID=id("0001"),STATUS_UUID=id("0002"),IMU_UUID=id("0003"),PRED_UUID=id("0004"),VOLT_UUID=id("0005"),SYNC_UUID=id("0006");
    static final UUID BAT_SERVICE=UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"),BAT_UUID=UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"),CCC=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    interface Listener {void onStatus(String s);void onConnected(boolean b);void onFrame(ImuFrame f,long ns);void onEvidence(String kind,String value,long ns);void onEpoch(int epoch,String reason);void onSync(int epoch,long t1,long t2,long t3,long t4);}
    private final Context context;private final Listener listener;private final BluetoothAdapter adapter;
    private final HandlerThread worker=new HandlerThread("pawlink-ble");private final Handler handler;
    private BluetoothLeScanner scanner;private BluetoothGatt gatt;private boolean scanning,closed,busy;private int retries,nonce;private volatile int epoch;
    private long lastDevice=-1;private BluetoothGattCharacteristic syncCharacteristic;private final Map<Integer,long[]> pending=new HashMap<>();
    private final ArrayDeque<Runnable> queue=new ArrayDeque<>();private final Runnable timeout=this::operationTimeout;
    private final List<long[]> observations=new ArrayList<>();
    BleClient(Context c,Listener l){context=c.getApplicationContext();listener=l;BluetoothManager m=c.getSystemService(BluetoothManager.class);adapter=m==null?null:m.getAdapter();worker.start();handler=new Handler(worker.getLooper());}
    int epoch(){return epoch;}
    synchronized List<long[]> syncObservations(){List<long[]> copy=new ArrayList<>();for(long[] p:observations)copy.add(p.clone());return copy;}
    boolean hasPermission(){return ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    private void newEpoch(String reason){epoch++;lastDevice=-1;pending.clear();listener.onEpoch(epoch,reason);}
    private void operationTimeout(){listener.onStatus("BLE 操作超时，正在恢复连接");recover();}
    private void recover(){disconnectGatt();listener.onConnected(false);if(!closed)handler.postDelayed(this::scan,Math.min(15000,2500L*(1+Math.min(5,retries++))));}
    void scanAndConnect(){handler.post(()->{closed=false;retries=0;scan();});}
    private void scan(){if(closed)return;if(!hasPermission()){listener.onStatus("请允许附近设备权限");return;}if(adapter==null||!adapter.isEnabled()){listener.onStatus("蓝牙未开启，等待恢复");handler.postDelayed(this::scan,5000);return;}stopScan();disconnectGatt();listener.onConnected(false);scanner=adapter.getBluetoothLeScanner();if(scanner==null){handler.postDelayed(this::scan,5000);return;}scanning=true;listener.onStatus("正在寻找 PawLink-Test…");scanner.startScan(scanCallback);handler.postDelayed(scanTimeout,15000);}
    private final Runnable scanTimeout=this::scanExpired;
    private void scanExpired(){if(scanning){stopScan();listener.onStatus("未找到项圈，继续重试");if(!closed)handler.postDelayed(this::scan,5000);}}
    private void stopScan(){handler.removeCallbacks(scanTimeout);if(scanning&&scanner!=null&&hasPermission())scanner.stopScan(scanCallback);scanning=false;}
    private void disconnectGatt(){handler.removeCallbacks(timeout);handler.removeCallbacks(periodicSync);queue.clear();pending.clear();busy=false;syncCharacteristic=null;if(gatt!=null&&hasPermission()){BluetoothGatt old=gatt;gatt=null;old.disconnect();old.close();}}
    void disconnect(){handler.post(()->{closed=true;handler.removeCallbacksAndMessages(null);stopScan();disconnectGatt();listener.onConnected(false);worker.quitSafely();});}
    private void next(){handler.removeCallbacks(timeout);busy=false;if(!queue.isEmpty()&&gatt!=null){busy=true;handler.postDelayed(timeout,8000);queue.remove().run();}}
    private void subscribe(BluetoothGattCharacteristic c){if(c==null)return;queue.add(()->{BluetoothGattDescriptor d=c.getDescriptor(CCC);if(d==null||!gatt.setCharacteristicNotification(c,true)){listener.onStatus("无法订阅 "+c.getUuid());next();return;}boolean ok;if(Build.VERSION.SDK_INT>=33)ok=gatt.writeDescriptor(d,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)==BluetoothStatusCodes.SUCCESS;else{d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);ok=gatt.writeDescriptor(d);}if(!ok)recover();});if((c.getProperties()&BluetoothGattCharacteristic.PROPERTY_READ)!=0)queue.add(()->{if(!gatt.readCharacteristic(c))next();});}
    private boolean write(BluetoothGattCharacteristic c,byte[] bytes){if(gatt==null)return false;if(Build.VERSION.SDK_INT>=33)return gatt.writeCharacteristic(c,bytes,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==BluetoothStatusCodes.SUCCESS;c.setValue(bytes);c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);return gatt.writeCharacteristic(c);}
    void shutdown(){handler.post(()->{if(gatt==null){listener.onStatus("项圈未连接");return;}BluetoothGattService s=gatt.getService(SERVICE_UUID);BluetoothGattCharacteristic c=s==null?null:s.getCharacteristic(STATUS_UUID);if(c==null)return;queue.add(()->{boolean ok=write(c,"SHUTDOWN".getBytes(StandardCharsets.UTF_8));listener.onStatus(ok?"等待设备确认关机":"关机发送失败");if(!ok)next();});if(!busy)next();});}
    private final Runnable periodicSync=new Runnable(){public void run(){if(closed||gatt==null||syncCharacteristic==null)return;for(int i=0;i<5;i++){final int connection=epoch;handler.postDelayed(()->{if(epoch==connection&&gatt!=null&&syncCharacteristic!=null)requestSync();},i*300L);}handler.postDelayed(this,30000);}};
    void synchronizeNow(){handler.post(()->{if(syncCharacteristic==null){listener.onStatus("当前固件没有同步通道，请更新固件");return;}handler.removeCallbacks(periodicSync);periodicSync.run();});}
    private void requestSync(){queue.add(()->{pending.entrySet().removeIf(e->SystemClock.elapsedRealtimeNanos()-e.getValue()[1]>5_000_000_000L);int n=++nonce;long t1=SystemClock.elapsedRealtimeNanos();pending.put(n,new long[]{epoch,t1});if(!write(syncCharacteristic,ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array())){pending.remove(n);next();}});if(!busy)next();}
    private final ScanCallback scanCallback=new ScanCallback(){@Override public void onScanResult(int type,ScanResult r){handler.post(()->{if(!scanning||!hasPermission())return;ScanRecord sr=r.getScanRecord();if(sr!=null&&("PawLink-Test".equals(sr.getDeviceName())||(sr.getServiceUuids()!=null&&sr.getServiceUuids().contains(new ParcelUuid(SERVICE_UUID))))){stopScan();gatt=r.getDevice().connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);handler.postDelayed(timeout,10000);}});}@Override public void onScanFailed(int code){handler.post(()->{scanning=false;listener.onStatus("扫描失败，稍后重试："+code);handler.postDelayed(()->scan(),5000);});}};
    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state){handler.post(()->{if(g!=gatt)return;if(state==BluetoothProfile.STATE_CONNECTED&&status==0){retries=0;newEpoch("connected");listener.onConnected(true);handler.removeCallbacks(timeout);handler.postDelayed(timeout,10000);if(!g.discoverServices())recover();}else if(state==BluetoothProfile.STATE_DISCONNECTED||status!=0){listener.onEvidence("connection","disconnected",SystemClock.elapsedRealtimeNanos());recover();}});}
        @Override public void onServicesDiscovered(BluetoothGatt g,int status){handler.post(()->{if(g!=gatt)return;handler.removeCallbacks(timeout);BluetoothGattService s=g.getService(SERVICE_UUID);if(status!=0||s==null){recover();return;}subscribe(s.getCharacteristic(IMU_UUID));subscribe(s.getCharacteristic(STATUS_UUID));subscribe(s.getCharacteristic(PRED_UUID));subscribe(s.getCharacteristic(VOLT_UUID));syncCharacteristic=s.getCharacteristic(SYNC_UUID);subscribe(syncCharacteristic);BluetoothGattService b=g.getService(BAT_SERVICE);if(b!=null)subscribe(b.getCharacteristic(BAT_UUID));if(syncCharacteristic==null)listener.onStatus("固件缺少同步通道：仅可原始采集，请更新固件");else handler.postDelayed(periodicSync,2000);next();});}
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){handler.post(()->{if(g!=gatt)return;if(status!=0){recover();return;}next();});}
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){handler.post(()->{if(g==gatt){if(status!=0){recover();return;}next();}});}
        @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] v,int status){long ns=SystemClock.elapsedRealtimeNanos();byte[] copy=v==null?null:v.clone();handler.post(()->{if(g!=gatt)return;if(status==0)handle(c.getUuid(),copy,ns,false);next();});}
        @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,int status){if(Build.VERSION.SDK_INT<33)onCharacteristicRead(g,c,c.getValue(),status);}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] v){long ns=SystemClock.elapsedRealtimeNanos();byte[] copy=v==null?null:v.clone();handler.post(()->{if(g==gatt)handle(c.getUuid(),copy,ns,true);});}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){if(Build.VERSION.SDK_INT<33)onCharacteristicChanged(g,c,c.getValue());}
    };
    private void handle(UUID id,byte[] v,long ns,boolean notification){if(v==null)return;
        if(IMU_UUID.equals(id)&&v.length==20){if(!notification)return;ByteBuffer b=ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN);long sequence=Integer.toUnsignedLong(b.getInt()),device=Integer.toUnsignedLong(b.getInt());if(lastDevice>=0&&device<lastDevice){newEpoch(lastDevice-device>0x80000000L?"clock_wrap":"device_restart");handler.removeCallbacks(periodicSync);handler.post(periodicSync);}lastDevice=device;listener.onFrame(new ImuFrame(sequence,device,b.getShort()/1000f,b.getShort()/1000f,b.getShort()/1000f,b.getShort()/100f,b.getShort()/100f,b.getShort()/100f),ns);}
        else if(SYNC_UUID.equals(id)&&v.length==12){ByteBuffer b=ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN);int n=b.getInt();long t2=Integer.toUnsignedLong(b.getInt()),t3=Integer.toUnsignedLong(b.getInt());long[] request=pending.remove(n);if(request!=null&&request[0]==epoch&&t3>=t2){long[] observation={epoch,request[1],t2,t3,ns};synchronized(this){observations.add(observation);if(observations.size()>500)observations.remove(0);}listener.onSync(epoch,request[1],t2,t3,ns);}}
        else if(PRED_UUID.equals(id))listener.onEvidence("prediction",new String(v,StandardCharsets.UTF_8),ns);
        else if(STATUS_UUID.equals(id)){String s=new String(v,StandardCharsets.UTF_8);if(s.contains("SHUTTING_DOWN")){closed=true;listener.onStatus("设备已确认关机，按 Reset 重新启动");}listener.onEvidence("status",s,ns);}
        else if(VOLT_UUID.equals(id)&&v.length==2)listener.onEvidence("voltage",String.valueOf(Short.toUnsignedInt(ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN).getShort())),ns);
        else if(BAT_UUID.equals(id)&&v.length==1)listener.onEvidence("battery",String.valueOf(Byte.toUnsignedInt(v[0])),ns);
    }
}
