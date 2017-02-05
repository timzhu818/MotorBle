package rjt.example.com.motorble;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.UUID;

public class MainActivity extends AppCompatActivity{

    private static final String LOG_TAG="MotorBle_Activity";
    private static final long SCAN_PERIOD=10*1000L;
    private static final long HEARTBEAT_RESPONSE_DELAY=100L;
    private static final int REQUEST_ENABLE_BT=1;
    private static final int MASK_MOTOR_LEFT=0x01;
    private static final int MASK_MOTOR_RIGHT=0x02;
    private static final int MASK_HEARTBEAT=0x04;

    public static final UUID RFDUINO_SERVICE_UUID=UUID.fromString("00002220-0000-1000-8000-00805F9B34FB");
    public static final UUID RECEIVE_CHARACTERISTIC_UUID=UUID.fromString("00002221-0000-1000-8000-00805F9B34FB");
    public static final UUID SEND_CHARACTERISTIC_UUID=UUID.fromString("00002222-0000-1000-8000-00805F9B34FB");
    public static final UUID DISCONNECT_CHARACTERISTIC_UUID=UUID.fromString("00002223-0000-1000-8000-00805F9B34FB");
    // 0x2902 org.bluetooth.descriptor.gatt.client_characteristic_configuration.xml
    public static final UUID CLIENT_CHARACTERISTIC_CONFIGURATION_UUID=UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private TextView connStatusTV;
    private Button doubleForwardButton,leftForwardButton,rightForwardButton,stopButton;
    private Handler handler;
    private BluetoothAdapter bluetoothAdapter;
    private boolean scanning=false;
    private BluetoothDevice gattExploreDevice;
    private BluetoothGatt gatt;
    private BluetoothGattService gattService;
    private BluetoothGattCharacteristic receiveCharacteristic,sendCharacteristic,disconnectCharacteristic;
    private GattExploreFoundTask geFoundTask;
    private StopStartLEScanTask leScanTask;
    private GattExploreDisconnectedTask geDisconnectedTask;
    private GattExplorerConnectedTask geConnectedTask;
    private GattExplorerDiscoveredTask geDiscoveredTask;
    private SendHeartbeatTask heartBeatTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        connStatusTV=(TextView)findViewById(R.id.connstatus);
        doubleForwardButton=(Button)findViewById(R.id.ib_doubleforward);
        leftForwardButton=(Button)findViewById(R.id.ib_leftforward);
        rightForwardButton=(Button)findViewById(R.id.ib_rightforward);
        stopButton=(Button)findViewById(R.id.ib_stop);
        handler=new Handler();
        leScanTask=new StopStartLEScanTask();
        geFoundTask=new GattExploreFoundTask();
        geDisconnectedTask=new GattExploreDisconnectedTask();
        geConnectedTask=new GattExplorerConnectedTask();
        geDiscoveredTask=new GattExplorerDiscoveredTask();
        heartBeatTask=new SendHeartbeatTask();

        if(!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)){
            Toast.makeText(this, "BLE not Supported!", Toast.LENGTH_SHORT).show();
            finish();
        }
        final BluetoothManager bluetoothManager=
                (BluetoothManager)(getSystemService(Context.BLUETOOTH_SERVICE));
        bluetoothAdapter=bluetoothManager.getAdapter();
        if(bluetoothAdapter==null){
            Toast.makeText(this,"Bluetooth not supported",Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        doubleForwardButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(gattService!=null){
                    forward();
                }
            }
        });
        leftForwardButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(gattService!=null){
                    leftForward();
                }
            }
        });
        rightForwardButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(gattService!=null){
                    rightForward();
                }
            }
        });
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if( gattService != null ){
                    stop();
                }
            }
        });
    }

    private void forward(){
        sendCharacteristic.setValue(MASK_MOTOR_LEFT|MASK_MOTOR_RIGHT,BluetoothGattCharacteristic.FORMAT_UINT8,0);
        boolean initiated=gatt.writeCharacteristic(sendCharacteristic);
        Log.d(LOG_TAG, "Forward: "+initiated);
    }
    private void leftForward(){
        sendCharacteristic.setValue(MASK_MOTOR_LEFT,BluetoothGattCharacteristic.FORMAT_UINT8,0);
        boolean initiated=gatt.writeCharacteristic(sendCharacteristic);
        Log.d(LOG_TAG,"Right Forward: "+initiated);
    }
    private void rightForward(){
        sendCharacteristic.setValue(MASK_MOTOR_RIGHT,BluetoothGattCharacteristic.FORMAT_UINT8,0);
        boolean initiated=gatt.writeCharacteristic(sendCharacteristic);
        Log.d(LOG_TAG,"Left Forward: "+initiated);
    }
    private void stop(){
        sendCharacteristic.setValue(0,BluetoothGattCharacteristic.FORMAT_UINT8,0);
        boolean initiated=gatt.writeCharacteristic(sendCharacteristic);
        Log.d(LOG_TAG,"Stop: "+initiated);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(!bluetoothAdapter.isEnabled()){
            if (!bluetoothAdapter.isEnabled()){
                Intent enableBtIntent=new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent,REQUEST_ENABLE_BT);
            }
        }
        scanLeDevice(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        disconnectMotor();
        scanLeDevice(false);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_screen,menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem rescanItem=menu.findItem(R.id.rescan);
        rescanItem.setEnabled(gatt==null);
        MenuItem disconnectItem=menu.findItem(R.id.disconnect);
        disconnectItem.setEnabled(gatt!=null);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id=item.getItemId();
        if(id==R.id.rescan)
            scanLeDevice(true);
        else if(id==R.id.disconnect){
            disconnectMotor();
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode==REQUEST_ENABLE_BT&&resultCode==Activity.RESULT_CANCELED){
            finish();
            return;
        }
    }

    private void disconnectMotor(){
        if(gatt!=null){
            disconnectCharacteristic.setValue("");
            disconnectCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            gatt.writeCharacteristic(disconnectCharacteristic);
            gatt.disconnect();
            gatt=null;
            gattService=null;
        }
    }

    private void scanLeDevice(final boolean enable) {
        Log.d(LOG_TAG, "scanLeDevice: " + enable);
        if(enable){
            gattExploreDevice=null;
            gatt=null;
            gattService=null;
            searchingUI();
            Log.d(LOG_TAG, "scanLeDevice(scan stop scheduled after): " + Long.toString(SCAN_PERIOD) + "msec");
            handler.postDelayed(leScanTask,SCAN_PERIOD);
            scanning=true;
        }else{
            scanning=false;
            idleUI();
            handler.removeCallbacks(leScanTask);
            bluetoothAdapter.stopLeScan(leScanCallback);
        }
    }

    private void idleUI() {
        connStatusTV.setText("Idle");
        disableButtons();
    }

    private void searchingUI() {
        connStatusTV.setText("Searching motor...");
        disableButtons();
    }

    private void disableButtons() {
        doubleForwardButton.setEnabled(false);
        leftForwardButton.setEnabled(false);
        rightForwardButton.setEnabled(false);
        stopButton.setEnabled(false);
    }

    private void enableButtons(){
        doubleForwardButton.setEnabled(true);
        leftForwardButton.setEnabled(true);
        rightForwardButton.setEnabled(true);
        stopButton.setEnabled(true);
    }

    private BluetoothAdapter.LeScanCallback leScanCallback=new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            String deviceName = device.getName();
            Log.d(LOG_TAG, "Device found: " + deviceName);
            if ("motor_BLE".equals(deviceName)) {
                gattExploreDevice = device;
                MainActivity.this.runOnUiThread(geFoundTask);
                gattExploreDevice.connectGatt(MainActivity.this,false,gattCallback);
            }
        }
    };

    private BluetoothGattCallback gattCallback=new BluetoothGattCallback() {
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            String success=status==BluetoothGatt.GATT_SUCCESS?"Success":"Failure";
            String message="Write operation on "+characteristic.getUuid().toString()+" : "+success;
            Log.d(LOG_TAG, "status: "+status+" : "+message);
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if(newState== BluetoothProfile.STATE_DISCONNECTED){
                MainActivity.this.gatt=null;
                MainActivity.this.runOnUiThread(geDisconnectedTask);
            }else if(newState==BluetoothProfile.STATE_CONNECTED){
                MainActivity.this.gatt=gatt;
                MainActivity.this.runOnUiThread(geConnectedTask);
                boolean success=gatt.discoverServices();
                Log.d(LOG_TAG, "discoverServices: "+success);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if(status==BluetoothGatt.GATT_SUCCESS){
                Log.d(LOG_TAG, "services discovered.");
                gattService=gatt.getService(RFDUINO_SERVICE_UUID);
                receiveCharacteristic=gattService.getCharacteristic(RECEIVE_CHARACTERISTIC_UUID);
                sendCharacteristic=gattService.getCharacteristic(SEND_CHARACTERISTIC_UUID);
                disconnectCharacteristic=gattService.getCharacteristic(DISCONNECT_CHARACTERISTIC_UUID);
                gatt.setCharacteristicNotification(receiveCharacteristic,true);
                BluetoothGattDescriptor receiveConfigDescriptor=receiveCharacteristic
                        .getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION_UUID);
                if(receiveConfigDescriptor!=null){
                    receiveConfigDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(receiveConfigDescriptor);
                }else{
                    Log.e(LOG_TAG, "Receive Characteristic can not be configured.");
                }
                MainActivity.this.runOnUiThread(geDiscoveredTask);
            }else{
                Log.w(LOG_TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            Object o=characteristic.getValue();
            String message="Characteristic changed: "+characteristic.getUuid().toString();
            if(o instanceof byte[]){
                byte b[]=(byte[])o;
                if(b.length==1){
                    if((b[0]&MASK_HEARTBEAT)!=0){
                        handler.postDelayed(heartBeatTask,HEARTBEAT_RESPONSE_DELAY);
                    }else if((b[0]>=0)&&b[0]<=3){
                        Log.d( LOG_TAG, "motor status: "+b[0]);
                    }
                }else
                    message=message+((byte[]) o).toString();
                Log.d(LOG_TAG, message);
            }
        }

    };

    class GattExploreFoundTask implements Runnable{
        @Override
        public void run() {
            connStatusTV.setText("motor_BLE found, connecting...");
        }
    }

    class GattExplorerDiscoveredTask implements Runnable{

        @Override
        public void run() {
            connStatusTV.setText("motor_BLE Ready");
            enableButtons();
        }
    }

    class GattExploreDisconnectedTask implements Runnable{

        @Override
        public void run() {
            connStatusTV.setText("motor Disconnected!");
            disableButtons();
        }
    }

    class GattExplorerConnectedTask implements Runnable{
        @Override
        public void run() {
            connStatusTV.setText("motor Connected!");
        }
    }

    class SendHeartbeatTask implements Runnable{
        @Override
        public void run() {
            if(gatt!=null){
                sendCharacteristic.setValue(MASK_HEARTBEAT, BluetoothGattCharacteristic.FORMAT_UINT8, 0);
                sendCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                boolean initiated=gatt.writeCharacteristic(sendCharacteristic);
                Log.d(LOG_TAG, "heartbeat: "+initiated);
            }
        }
    }

    class StopStartLEScanTask implements Runnable{
        @Override
        public void run() {
            Log.d(LOG_TAG, "StopStartLEScanTask.run() at: " + Long.toString(System.currentTimeMillis()));
            bluetoothAdapter.stopLeScan(leScanCallback);
            if(gattExploreDevice==null){
                bluetoothAdapter.startLeScan(leScanCallback);
                Log.d(LOG_TAG, "(run) scan stop scheduled after: " + Long.toString(SCAN_PERIOD) + "msec");
                handler.postDelayed(leScanTask,SCAN_PERIOD);
            }
        }
    }
}
