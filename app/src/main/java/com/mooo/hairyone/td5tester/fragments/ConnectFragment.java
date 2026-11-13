package com.mooo.hairyone.td5tester.fragments;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import com.mooo.hairyone.td5tester.Consts;
import com.mooo.hairyone.td5tester.Conversions;
import com.mooo.hairyone.td5tester.FTDI;
import com.mooo.hairyone.td5tester.FaultCodes;
import com.mooo.hairyone.td5tester.Faults;
import com.mooo.hairyone.td5tester.Log4jHelper;
import com.mooo.hairyone.td5tester.LogRecord;
import com.mooo.hairyone.td5tester.R;
import com.mooo.hairyone.td5tester.Requests;
import com.mooo.hairyone.td5tester.Util;
import com.mooo.hairyone.td5tester.events.ConnectedEvent;
import com.mooo.hairyone.td5tester.events.DashboardEvent;
import com.mooo.hairyone.td5tester.events.MessageEvent;
import com.mooo.hairyone.td5tester.events.NotConnectedEvent;
import com.mooo.hairyone.td5tester.events.FaultsEvent;
import com.mooo.hairyone.td5tester.events.RunActuatorEvent;
import com.mooo.hairyone.td5tester.events.ToggleUIEvent;
import com.mooo.hairyone.td5tester.events.VehicleInfoEvent;

import org.apache.log4j.Logger;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;


public class ConnectFragment extends BaseFragment {

    Logger log = Log4jHelper.getLogger(this.getClass());

    private static final String STATE__INFO_LINES = "INFO_LINES";

    TextView tvInfo;
    ImageButton btConnect;
    TextView tvStatus;
    TextView tvVin;
    TextView tvFaults;
    /*    ImageButton btFastInit;
    @BindView(R.id.btDashboard) ImageButton btDashboard;*/

    byte[] mReadBuffer = new byte[Consts.RESPONSE_BUFFER_SIZE];
    boolean mHaveUsbPermission = false;
    boolean mFastInitCompleted = false;
    private volatile boolean mDashboardRunning = false;
    Requests mRequests = new Requests();
    UsbDevice mUsbDevice = null;
    UsbInterface mUsbInterface = null;
    UsbEndpoint mUsbEndpointIn = null;
    UsbEndpoint mUsbEndpointOut = null;
    UsbDeviceConnection mUsbDeviceConnection = null;

    private ArrayList<String> mInfoLines = new ArrayList<String>();
    boolean mFakeUsbDeviceConnection = false;

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Consts.ACTION_USB_PERMISSION.equals(action)) {
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                Util.log_msg(String.format("permission granted for %s", usbDevice.getProductName()));
                mHaveUsbPermission = true;
            }
        }
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            mUsbDevice = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            Util.log_msg(String.format("usb_attached=%s", mUsbDevice.getProductName()));
        }
        if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            UsbDevice usbDevice = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            Util.log_msg(String.format("usb_detached=%s", usbDevice.getProductName()));
            if (usbDevice != null && mUsbDevice != null && usbDevice.getDeviceName().equals(mUsbDevice.getDeviceName())) {
                usb_close();
            }
        }
        }
    };

    public ConnectFragment() {
        // Required empty public constructor
    }

    @Override
    public void onResume() {
        super.onResume();
        // Android 14 requires the export flag on every non-system receiver.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Consts.ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(getActivity(), mBroadcastReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onPause() {
        getActivity().unregisterReceiver(mBroadcastReceiver);
        super.onPause();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRunActuatorEvent(RunActuatorEvent event) {
        Thread thread = new Thread(() -> run_actuator(event.pid, event.label));
        thread.start();
    }

    private void run_actuator(Requests.RequestPidEnum pid, String label) {
        EventBus.getDefault().post(new ToggleUIEvent(false));
        stopDashboard();
        if (!mFastInitCompleted) {
            Util.log_msg(String.format("%s : impossible, le FASTINIT n'a pas abouti.", label));
        } else if (Consts.DEBUG_UI) {
            Util.log_msg(String.format("%s : simulé (mode démo).", label));
            try { Thread.sleep(800); } catch (Exception ex) { }
        } else {
            boolean ok = get_pid(pid);
            Util.log_msg(String.format("%s : %s", label, ok ? "commande acceptée" : "refusée par le calculateur"));
        }
        startDashboard();
        EventBus.getDefault().post(new ToggleUIEvent(true));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onFaultsEvent(FaultsEvent event) {
        if (tvFaults == null) return;
        if (event.faults.isEmpty()) {
            tvFaults.setText(R.string.faults_none);
            tvFaults.setTextColor(getResources().getColor(R.color.stateNormal));
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Faults.Fault fault : event.faults) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(fault.code()).append("  ").append(fault.label);
        }
        tvFaults.setText(sb.toString());
        tvFaults.setTextColor(getResources().getColor(R.color.stateAlert));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onVehicleInfoEvent(VehicleInfoEvent event) {
        if (tvVin == null) return;
        StringBuilder sb = new StringBuilder();
        if (!event.vin.isEmpty()) sb.append("VIN  ").append(event.vin);
        if (!event.ecuType.isEmpty()) sb.append(sb.length() > 0 ? "\n" : "").append("ECU  ").append(event.ecuType);
        if (!event.mapVariant.isEmpty()) sb.append(sb.length() > 0 ? "\n" : "").append("CARTO  ").append(event.mapVariant);
        tvVin.setText(sb.length() > 0 ? sb.toString() : getString(R.string.dash_placeholder));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(MessageEvent event) {
        addInfoLine(event.message);
        log.info(event.message);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onNotConnectedEvent(NotConnectedEvent event) {
        btConnect.setImageResource(R.drawable.ic_plug);
        if (tvStatus != null) tvStatus.setText(R.string.status_disconnected);
        EventBus.getDefault().post(new ToggleUIEvent(true));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onToggleUIEvent(ToggleUIEvent event) {
        btConnect.setEnabled(event.enable);
        /*btFastInit.setEnabled(event.enable);
        btDashboard.setEnabled(event.enable);*/
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConnectedEvent(ConnectedEvent event) {
        btConnect.setImageResource(R.drawable.ic_plug);
        if (tvStatus != null) tvStatus.setText(R.string.status_connected);
        EventBus.getDefault().post(new ToggleUIEvent(true));
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);
        log.debug("");
        Util.log_msg("onSaveInstanceState");
        //stopDashboard();
        //savedInstanceState.putStringArrayList(STATE__INFO_LINES, mInfoLines);
    }

    @Override public void onViewStateRestored(Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        if (savedInstanceState != null) {
            mInfoLines = savedInstanceState.getStringArrayList((STATE__INFO_LINES));
            Util.log_msg("onViewStateRestored");
            startDashboard();
        }
    }

    @Override protected int getFragmentLayout() {
        return R.layout.connect_fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        log.trace("");
        View view =  inflater.inflate(R.layout.connect_fragment, container, false);
        bindViews(view);
        tvInfo.setMovementMethod(new ScrollingMovementMethod());
        return view;
   }

    public void connectHandler(View view) {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                if (mUsbDeviceConnection != null || mFakeUsbDeviceConnection) {
                    stopDashboard();
                    usb_close();
                } else {
                    usb_open();
                    fast_init();
                    startDashboard();
                }
            }
        });
        thread.start();
    }

    /*    public void fastinitHandler() {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                fast_init();
            }
        });
        thread.start();
    }
*/
    public void readFaultsHandler(View view) {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                read_faults();
            }
        });
        thread.start();
    }

    public void clearFaultsHandler(View view) {
        new android.app.AlertDialog.Builder(requireContext(), R.style.InfoDialog)
                .setTitle(R.string.clear_faults_title)
                .setMessage(R.string.clear_faults_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear_faults_confirm, (d, w) -> clearFaultsConfirmed())
                .show();
    }

    private void clearFaultsConfirmed() {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                clear_faults();
            }
        });
        thread.start();
    }
/*
    public void pulserevHandler(View view) {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                pulserev();
            }
        });
        thread.start();
    }

    public void dashboardHandler() {
        if (mDashboardRunning) {
            stopDashboard();
        } else {
            startDashboard();
        }
    }
*/
    private boolean usb_open() {
        EventBus.getDefault().post(new ToggleUIEvent(false));
        Util.log_msg("USB connecting ...");

        if (Consts.DEBUG_UI) {
            try { Thread.sleep(2000); } catch (Exception ex) {};
            Util.log_msg("USB connected!");
            mFakeUsbDeviceConnection = true;
            EventBus.getDefault().post(new ConnectedEvent());
            return true;
        }

        boolean result = false;
        mFastInitCompleted = false;
        // mDashboardRunning = false;
        mFakeUsbDeviceConnection = false;
        mUsbDevice = null;
        mUsbInterface = null;
        mUsbEndpointIn = null;
        mUsbEndpointOut = null;
        mUsbDeviceConnection = null;
        stopDashboard();

        UsbManager manager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> deviceList = manager.getDeviceList();
        Iterator<UsbDevice> deviceIterator = deviceList.values().iterator();
        while (deviceIterator.hasNext()) {
            UsbDevice usbDevice = deviceIterator.next();
            if (usbDevice.getVendorId() == FTDI.VENDOR_ID && usbDevice.getProductId() == FTDI.PRODUCT_ID) {
                mUsbDevice = usbDevice;
            }
        }

        if (mUsbDevice == null) {
            Util.log_msg("FTDI device not found");
            EventBus.getDefault().post(new NotConnectedEvent());
            return result;
        }

        Util.log_msg(String.format("connected to %s", mUsbDevice.getProductName()));

        // Asking again for a device we already hold is what made the dialog
        // appear on every single connection.
        mHaveUsbPermission = manager.hasPermission(mUsbDevice);
        if (!mHaveUsbPermission) {
            Util.log_msg(String.format("requesting permission for %s", mUsbDevice.getProductName()));
            int flags = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_IMMUTABLE : 0;
            Intent intent = new Intent(Consts.ACTION_USB_PERMISSION).setPackage(
                    requireContext().getPackageName());
            manager.requestPermission(mUsbDevice,
                    PendingIntent.getBroadcast(getContext(), 0, intent, flags));
        }

        // FIXME: The rest of this code should be fired in another thread when permission is granted
        int cnt = 0;
        while (!mHaveUsbPermission && cnt <= Consts.PERMISSION_WAIT_SECS) {
            Util.log_msg(String.format("waiting for permission for %s", mUsbDevice.getProductName()));
            try {
                Thread.sleep(1000);
            } catch (Exception ex) {
                Util.log_msg(ex.toString());
                break;
            }
            cnt++;
        }
        if (!mHaveUsbPermission) {
            EventBus.getDefault().post(new NotConnectedEvent());
            return result;
        }

        for (int i=0; i < mUsbDevice.getInterfaceCount(); i++) {
            UsbInterface usbInterface = mUsbDevice.getInterface(i);
            UsbEndpoint usbEndpointOut = null;
            UsbEndpoint usbEndpointIn = null;

            int endpointCount = usbInterface.getEndpointCount();
            if (endpointCount >= 2) {
                for (int j = 0; j < endpointCount; j++) {
                    if (usbInterface.getEndpoint(j).getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && usbInterface.getEndpoint(j).getDirection() == UsbConstants.USB_DIR_OUT) {
                        usbEndpointOut = usbInterface.getEndpoint(j);
                    }
                    if (usbInterface.getEndpoint(j).getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && usbInterface.getEndpoint(j).getDirection() == UsbConstants.USB_DIR_IN) {
                        usbEndpointIn = usbInterface.getEndpoint(j);
                    }
                }
                if (usbEndpointOut != null && usbEndpointIn != null) {
                    mUsbInterface = usbInterface;
                    mUsbEndpointOut = usbEndpointOut;
                    mUsbEndpointIn = usbEndpointIn;
                }
            }
        }

        if( mUsbInterface == null) {
            Util.log_msg("no suitable FTDI interface found");
            EventBus.getDefault().post(new NotConnectedEvent());
            return result;
        }

        Util.log_msg(String.format("mUsbInterface=%s", mUsbInterface.toString().replaceAll("(\\r|\\n)", "")));
        Util.log_msg("USB connected!");
        mUsbDeviceConnection = manager.openDevice(mUsbDevice);
        if (mUsbDeviceConnection != null) {
            mUsbDeviceConnection.claimInterface(mUsbInterface, true);
            control_transfer(FTDI.SIO_RESET,         FTDI.SIO_RESET_SIO,         FTDI.CH_A);
            control_transfer(FTDI.SIO_RESET,         FTDI.SIO_RESET_PURGE_RX,    FTDI.CH_A);
            control_transfer(FTDI.SIO_RESET,         FTDI.SIO_RESET_PURGE_TX,    FTDI.CH_A);
            control_transfer(FTDI.SIO_SET_FLOW_CTRL, FTDI.SIO_DISABLE_FLOW_CTRL, FTDI.CH_A);
            control_transfer(FTDI.SIO_SET_DATA,      FTDI.LINE_8N1,              FTDI.CH_A);
            control_transfer(FTDI.SIO_SET_BAUDRATE,  FTDI.BAUDRATE_10400);
            result = true;
        }

        EventBus.getDefault().post(new ConnectedEvent());
        return result;
    }

    private void usb_close() {
        EventBus.getDefault().post(new ToggleUIEvent(false));
        stopDashboard();
        Util.log_msg("USB disconnecting ...");

        if (Consts.DEBUG_UI) {
            try { Thread.sleep(2000); } catch (Exception ex) {};
            mFakeUsbDeviceConnection = false;
        } else {
            if (mUsbInterface != null) {
                if (mUsbInterface != null) {
                    mUsbDeviceConnection.releaseInterface(mUsbInterface);
                    mUsbInterface = null;
                }
            }
            if (mUsbDeviceConnection != null) {
                mUsbDeviceConnection.close();
                mUsbDeviceConnection = null;
            }
        }

        mFastInitCompleted = false;
        mFakeUsbDeviceConnection = false;
        mHaveUsbPermission = false;
        mUsbDevice = null;
        mUsbEndpointIn = null;
        mUsbEndpointOut = null;

        Util.log_msg("USB disconnected!");
        EventBus.getDefault().post(new NotConnectedEvent());
    }

    private int control_transfer(int request, int value) {
        return control_transfer(request, value, 0);
    }

    private int control_transfer(int request, int value, int index) {
        int rc = mUsbDeviceConnection.controlTransfer(FTDI.REQ_OUT, request, value, index, null, 0x00, FTDI.WRITE_TIMEOUT);
        if (rc < 0) {
            log.error(String.format("request=%d, value=%d, index=%d, rc=%d", request, value, index, rc));
        }
        return rc;
    }

    private int bulk_transfer_read(byte[] data, int len) {
        int rc = mUsbDeviceConnection.bulkTransfer(mUsbEndpointIn, data, len, FTDI.READ_TIMEOUT);
        if (rc < 0) {
           //log.error(String.format("data=%s, rc=%d", Util.byte_array_to_hex(data, len), rc));
        }
        return rc;
    }

    private int bulk_transfer_write(byte[] data, int len) {
        int rc = mUsbDeviceConnection.bulkTransfer(mUsbEndpointOut, data, len, FTDI.WRITE_TIMEOUT);
        if (rc < 0) {
            //log.error(String.format("data=%s, rc=%d", Util.byte_array_to_hex(data, len), rc));
        }
        return rc;
    }

    private void fast_init() {
        EventBus.getDefault().post(new ToggleUIEvent(false));
        stopDashboard();

        if (Consts.DEBUG_UI == false && mUsbDeviceConnection == null || Consts.DEBUG_UI && mFakeUsbDeviceConnection == false ) {
            Util.log_msg("FASTINIT cannot be executed until you have connected to a USB device!");
        } else {
            Util.log_msg(String.format("Performing FASTINIT ..."));

            if (Consts.DEBUG_UI) {
                try { Thread.sleep(2000); } catch (Exception ex) {};
                mFastInitCompleted = true;
            } else {
                byte[] HI = new byte[]{(byte) 0x01};
                byte[] LO = new byte[]{(byte) 0x00};

                control_transfer(FTDI.SIO_SET_BITMODE, FTDI.BITBANG_ON);

                try {
                    Util.log_msg("FASTINIT");
                    bulk_transfer_write(HI, 1);
                    Thread.sleep(500);
                    bulk_transfer_write(LO, 1);
                    Thread.sleep(25);
                    bulk_transfer_write(HI, 1);
                    Thread.sleep(25);
                    control_transfer(FTDI.SIO_SET_BITMODE, FTDI.BITBANG_OFF);

                    control_transfer(FTDI.SIO_RESET, FTDI.SIO_RESET_PURGE_RX, FTDI.CH_A);
                    control_transfer(FTDI.SIO_RESET, FTDI.SIO_RESET_PURGE_TX, FTDI.CH_A);

                    if (get_pid(Requests.RequestPidEnum.INIT_FRAME) && get_pid(Requests.RequestPidEnum.START_DIAGNOSTICS) && get_pid(Requests.RequestPidEnum.REQUEST_SEED)) {
                        // https://stackoverflow.com/questions/736815/2-bytes-to-short-java
                        short seed = Util.bytes2short(mReadBuffer[3], mReadBuffer[4]);
                        log.debug(String.format("seed=%X, seed_hi=%X, seed_lo=%X", seed, mReadBuffer[3], mReadBuffer[4]));
                        int key = generate_key(seed);
                        mRequests.request.get(Requests.RequestPidEnum.KEY_RETURN).request[3] = (byte) ((key & 0xFFFF) >>> 8);
                        mRequests.request.get(Requests.RequestPidEnum.KEY_RETURN).request[4] = (byte) (key & 0xFF);
                        mFastInitCompleted = get_pid(Requests.RequestPidEnum.KEY_RETURN); // && get_pid(Requests.RequestPidEnum.START_FUELLING);
                    }
                } catch (Exception ex) {
                    Util.log_msg(ex.toString());
                }
            }
            Util.log_msg(String.format("FASTINIT %s", mFastInitCompleted ? "OK" : "FAILED"));
            if (mFastInitCompleted) readVehicleInfo();
        }
        EventBus.getDefault().post(new ToggleUIEvent(true));
    }

    /*
    uint16_t generateKey(uint16_t seed) {
        uint16_t tmp = 0;
        byte tap = 0;
        byte count = ((seed >> 0xC & 0x8) | (seed >> 0x5 & 0x4) | (seed >> 0x3 & 0x2) | (seed & 0x1)) + 1;
        for (byte idx = 0; idx < count; idx++) {
            tap = ((seed >> 1 ) ^ (seed >> 2 ) ^ (seed >> 8 ) ^ (seed >> 9 )) & 1;
            tmp = ((seed >> 1) | (tap << 0xF));
            if ((seed >> 0x3 & 1) && (seed >> 0xD & 1)) {
                seed = tmp & ~1;
            } else {
                seed = tmp | 1;
            }
        }
        return seed;
    }
    */

    private int generate_key(int seedin) {
        int seed = seedin & 0xFFFF;
        log.debug(String.format("seed=%04X, seed_hi=%02X, seed_lo=%02X", seed & 0xFFFF, (seed & 0xFFFF) >>> 8, seed & 0xFF));
        int count = (( (seed >>> 0xC & 0x8) | (seed >>> 0x5 & 0x4) | (seed >>> 0x3 & 0x2) | (seed & 0x1) ) + 1) & 0xFF;
        log.debug(String.format("count=%d", count));
        for (int idx = 0; idx < count; idx++) {
            int tap = (( (seed >>> 1) ^ (seed >>> 2) ^ (seed >>> 8) ^ (seed >>> 9) ) & 1) & 0xFF;
            int tmp = (seed >>> 1) | (tap << 0xF);
            if ( (seed >>> 0x3 & 1) == 1 && (seed >>> 0xD & 1) == 1 ) {
                seed = tmp & ~1;
            } else {
                seed = tmp | 1;
            }
            log.debug(String.format("tap=%d, tmp=%05d, a=%d, b=%d, seed=%05d", tap, tmp, seed >>> 0x03 & 1, seed >>> 0x0d & 1, seed));
        }
        log.debug(String.format("key_hi=%02X, key_lo=%02X", (seed & 0xFFFF) >>> 8, seed & 0xFF));
        return seed;
    }

    private boolean get_pid(Requests.RequestPidEnum pid) {
        boolean result = false;
        if (pid != Requests.RequestPidEnum.INIT_FRAME) {
            try {
                Thread.sleep(Consts.SEND_REQUEST_DELAY);
            } catch (Exception ex) {
                Util.log_msg(String.format("error=%s", ex.toString()));
            }
        }
        sendRequest(pid);
        int len = readResponse(pid);
        if (len > 1) {
            byte cs1 = mReadBuffer[len - 1];
            byte cs2 = checksum(mReadBuffer, len - 1);
            if (cs1 == cs2) {
                if (mReadBuffer[1] != 0x7F) {
                    result = true;
                }
            }
        }
        return result;
    }

    private void sendRequest(Requests.RequestPidEnum pid) {
        int len = mRequests.request.get(pid).request.length;
        byte[] data = mRequests.request.get(pid).request;
        data[len - 1] = checksum(data, len - 1);

         String name = mRequests.request.get(pid).name;
         String msg = String.format(">> %s", Util.byte_array_to_hex(data, len));
         //if (mFastInitCompleted) {
         //    log.debug(name);
         //    log.debug(msg);
         //} else {
         //    Util.log_msg(name);
         //    Util.log_msg(msg);
         //}

        bulk_transfer_write(data, len);
    }

    private byte checksum(byte[] data, int len) {
        byte crc = 0;
        for (int i = 0; i < len; i++) {
            crc = (byte) (crc + data[i]);
        }
        return crc;
    }

    private int readResponse(Requests.RequestPidEnum pid) {

        int request_len = mRequests.request.get(pid).request.length;
        int response_len = mRequests.request.get(pid).response_len;
        int max_packet_size = mUsbEndpointIn.getMaxPacketSize();
        int modem_status_len = 2;

        final int modem_status_bytes_len = (
            (int) Math.ceil(
                ((double) request_len + response_len) / ((double) (max_packet_size - modem_status_len)))
        ) * modem_status_len;

        int expected_len = request_len + response_len + modem_status_bytes_len;
        byte[] buf = new byte[expected_len];

        long start_time = System.currentTimeMillis();
        int offset = 0;
        while (true) {
            // Keep reading until we get the expected number of bytes or we timeout
            int bytes_read = bulk_transfer_read(buf, expected_len);

            if (Consts.LOG_EVERY_READ && bytes_read > 0) {
                log.debug(String.format("<< %s", Util.byte_array_to_hex(buf, bytes_read)));
            }

            boolean line_status_error = (buf[1] & FTDI.ERROR_MASK) > 0;
            if (Consts.LOG_EVERY_MODEM_STATUS || line_status_error) {
                log.debug(String.format("modem_status=%s, line_status=%s, line_status_error=%b",
                    Util.integer_to_binary(buf[0]),
                    Util.integer_to_binary(buf[1]),
                    line_status_error
                ));
            }

            // There should be at least the two status bytes from the FTDI chip
            if (buf.length < 2) {
                log.error("malformed mReadBuffer less than 2 bytes read");
                return 0;
            }

            // Remove the modem status bytes from the start of each packet in the buffer
            int data_len = filter_modem_status_bytes(buf, buf, Math.min(buf.length, bytes_read), max_packet_size, modem_status_len);

            if (data_len > 0) {
                System.arraycopy(buf, 0, mReadBuffer, offset, data_len);
                offset += data_len;
            }

            if (pid == Requests.RequestPidEnum.KEY_RETURN && offset == 10 && mReadBuffer[7] == 0x67) {
                // The only instance where a negative response is longer than a positive response
                // >> 04 27 02 F7 B9 DD | 02 67 02 6B       Positive response
                // >> 04 27 02 F7 B9 DD | 03 7F 27 35 DE    Negative response
                break;
            }

            if (offset >= request_len + response_len || System.currentTimeMillis() - start_time > Consts.READ_RESPONSE_TIMEOUT ) {
                if (System.currentTimeMillis() - start_time > FTDI.READ_TIMEOUT) {
                    Util.log_msg("timeout in read_response()");
                }
                break;
            }
        }

        // Remove the echoed request
        if (offset >= request_len) {
            System.arraycopy(mReadBuffer, request_len, mReadBuffer, 0, offset - request_len);
            String msg = String.format("%s >> %s << %s",
                    mRequests.request.get(pid).name,
                    Util.byte_array_to_hex(mRequests.request.get(pid).request, request_len),
                    Util.byte_array_to_hex(mReadBuffer, offset - request_len)
            );
            log.debug(msg);
            if (Consts.DISPLAY_EVERY_REQUEST) {
                Util.log_msg(msg);
            }
            return offset - request_len;
        } else {
            // not enough bytes for a plausible response
            return 0;
        }
    }

    private int filter_modem_status_bytes(byte[] src, byte[] dest, int src_len, int max_packet_len, int modem_status_len) {
        int src_offset = 0;
        int dst_offset = 0;
        src_offset = src_offset + modem_status_len;
        while (src_offset < src_len) {
            int len = ((src_len - src_offset) >= (max_packet_len - modem_status_len)) ?
                    (max_packet_len - modem_status_len) :
                    (src_len - src_offset);
            System.arraycopy(src, src_offset, dest, dst_offset, len);
            src_offset = src_offset + len + modem_status_len;
            dst_offset = dst_offset + len;
        }
        return dst_offset;
    }

    private void dashboard() {

        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss");
        String csv_filename = this.getActivity().getExternalFilesDir(null) + File.separator + String.format("td5tester_%s.csv", simpleDateFormat.format(new Date()));
        LogRecord logRecord = new LogRecord();

        try (
            PrintWriter csv_filehandle = new PrintWriter(new FileOutputStream(new File(csv_filename), true))
        ) {
            Util.log_msg(String.format("Dashboard starting, logging to %s", csv_filename));
            simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            while (mDashboardRunning) {
                if (Consts.DEBUG_UI) {
                    simulate();
                    try { Thread.sleep(250); } catch (Exception ex) {}
                } else {
                    int pid_count = 0;
                    if (mDashboardRunning && get_pid(Requests.RequestPidEnum.ENGINE_RPM)) {
                        // 04 61 09 [03 06] 77
                        logRecord.EngineRpm = Util.bytes2short(mReadBuffer[3], mReadBuffer[4]);

                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.RPM, logRecord.EngineRpm));
                        pid_count++;
                    }
                    if (mDashboardRunning && get_pid(Requests.RequestPidEnum.BATTERY_VOLTAGE)) {
                        // 06 61 10 [36 2E] 35 FA 0A
                        logRecord.BatteryVoltage = Util.bytes2short(mReadBuffer[3], mReadBuffer[4]);

                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.BATTERY_VOLTAGE, Conversions.volts(logRecord.BatteryVoltage)));
                        pid_count++;
                    }
                    if (mDashboardRunning && get_pid(Requests.RequestPidEnum.VEHICLE_SPEED)) {
                        // 03 61 0D [00] 71
                        logRecord.VehicleSpeed = mReadBuffer[3];

                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.VEHICLE_SPEED, logRecord.VehicleSpeed ));
                        pid_count++;
                    }
                    if (mDashboardRunning && get_pid(Requests.RequestPidEnum.TEMPERATURES)) {
                        // The sensor on the airbox is AAP(ambient air pressure and temp) sensor and it has temp reading only on EU3 models.
                        // 12 61 1A [0C 80] 06 A4 [0C 52] 07 59 [10 88] 00 04 [0C 06] 08 A6 DD
                        logRecord.CoolantTemperature  = Util.bytes2short(mReadBuffer[ 3], mReadBuffer[ 4]);
                        logRecord.InletTemperature    = Util.bytes2short(mReadBuffer[ 7], mReadBuffer[ 8]);
                        logRecord.ExternalTemperature = Util.bytes2short(mReadBuffer[11], mReadBuffer[12]);
                        logRecord.FuelTemperature     = Util.bytes2short(mReadBuffer[15], mReadBuffer[16]);

                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.COOLANT_TEMP,  Conversions.temperatureCelsius(logRecord.CoolantTemperature)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.INLET_TEMP,    Conversions.temperatureCelsius(logRecord.InletTemperature)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.EXTERNAL_TEMP, Conversions.temperatureCelsius(logRecord.ExternalTemperature)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.FUEL_TEMP,     Conversions.temperatureCelsius(logRecord.FuelTemperature)));
                        pid_count++;
                    }
                    if (mDashboardRunning && get_pid(Requests.RequestPidEnum.THROTTLE_POSITION)) {
                        // 0A 61 1B [01 68] [12 1E] [00 00] [13 82] B4
                        logRecord.AcceleratorTrack1 = Util.bytes2short(mReadBuffer[3], mReadBuffer[4]);
                        logRecord.AcceleratorTrack2 = Util.bytes2short(mReadBuffer[5], mReadBuffer[6]);
                        logRecord.AcceleratorTrack3 = Util.bytes2short(mReadBuffer[7], mReadBuffer[8]);
                        logRecord.AcceleratorSupply = Util.bytes2short(mReadBuffer[9], mReadBuffer[10]);

                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.ACC_TRACK_1, Conversions.volts(logRecord.AcceleratorTrack1)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.ACC_TRACK_2, Conversions.volts(logRecord.AcceleratorTrack2)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.ACC_TRACK_3, Conversions.volts(logRecord.AcceleratorTrack3)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.ACC_SUPPLY,  Conversions.volts(logRecord.AcceleratorSupply)));
                        pid_count++;
                    }
                    if (mDashboardRunning && get_pid(Requests.RequestPidEnum.AMBIENT_PRESSURE)) {
                        logRecord.AmbientPressure = Util.bytes2short(mReadBuffer[3], mReadBuffer[4]);
                        // logRecord.AmbientPressureRaw = Util.bytes2short(mReadBuffer[5], mReadBuffer[6]);

                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.AMBIENT_PRESSURE, Conversions.pressureKpa(logRecord.AmbientPressure)));
                        //EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.AMBIENT_PRESSURE2, logRecord.AmbientPressureRaw / 100.0));
                        pid_count++;
                    }
                    if (mDashboardRunning && get_pid(Requests.RequestPidEnum.MAP_MAF)) {
                        logRecord.ManifoldAirPressure = Util.bytes2short(mReadBuffer[3], mReadBuffer[4]);
                        logRecord.ManifoldAirFlow = Util.bytes2short(mReadBuffer[7], mReadBuffer[8]);

                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.MANIFOLD_AIR_PRESSURE, Conversions.pressureKpa(logRecord.ManifoldAirPressure)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.AIR_FLOW, Conversions.airFlowKgH(logRecord.ManifoldAirFlow)));
                        pid_count++;
                    }
                    if (mDashboardRunning && get_pid(Requests.RequestPidEnum.POWER_BALANCE)) {
                        logRecord.PowerBalance1      = Util.bytes2short(mReadBuffer[ 3], mReadBuffer[ 4]);
                        logRecord.PowerBalance2      = Util.bytes2short(mReadBuffer[ 5], mReadBuffer[ 6]);
                        logRecord.PowerBalance3      = Util.bytes2short(mReadBuffer[ 7], mReadBuffer[ 8]);
                        logRecord.PowerBalance4      = Util.bytes2short(mReadBuffer[ 9], mReadBuffer[10]);
                        logRecord.PowerBalance5      = Util.bytes2short(mReadBuffer[11], mReadBuffer[12]);

                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.POWER_BALANCE_1, Conversions.powerBalance(logRecord.PowerBalance1)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.POWER_BALANCE_2, Conversions.powerBalance(logRecord.PowerBalance2)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.POWER_BALANCE_3, Conversions.powerBalance(logRecord.PowerBalance3)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.POWER_BALANCE_4, Conversions.powerBalance(logRecord.PowerBalance4)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.POWER_BALANCE_5, Conversions.powerBalance(logRecord.PowerBalance5)));


                        pid_count++;
                    }
                    if (mDashboardRunning && get_pid(Requests.RequestPidEnum.GET_FUEL_DEMAND)) {
                        logRecord.DriverDemand      = Util.bytes2short(mReadBuffer[ 3], mReadBuffer[ 4]);
                        logRecord.MafAirMass        = Util.bytes2short(mReadBuffer[ 5], mReadBuffer[ 6]);
                        logRecord.MapAirMass        = Util.bytes2short(mReadBuffer[ 7], mReadBuffer[ 8]);
                        logRecord.InjectionQuantity = Util.bytes2short(mReadBuffer[ 9], mReadBuffer[10]);
                        logRecord.AfRatio           = Util.bytes2short(mReadBuffer[11], mReadBuffer[12]);
                        // Ekaitza_Itzali labels word 6 as the smoke map and word 7 as the
                        // torque limiter, and hairyone's own logs agree: at warm idle the
                        // first is the lower of the two, as a smoke limit must be.
                        logRecord.SmokeLimit        = Util.bytes2short(mReadBuffer[13], mReadBuffer[14]);
                        logRecord.TorqueLimit       = Util.bytes2short(mReadBuffer[15], mReadBuffer[16]);
                        logRecord.IdleDemand        = Util.bytes2short(mReadBuffer[17], mReadBuffer[18]);

                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.DRIVER_DEMAND,      Conversions.fuelQuantityMg(logRecord.DriverDemand)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.MAF_AIR_MASS,       Conversions.airMassMg(logRecord.MafAirMass)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.MAP_AIR_MASS,       Conversions.airMassMg(logRecord.MapAirMass)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.INJECTION_QUANTITY, Conversions.fuelQuantityMg(logRecord.InjectionQuantity)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.AF_RATIO,           Conversions.airFuelRatio(logRecord.AfRatio)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.TORQUE_LIMIT,       Conversions.fuelQuantityMg(logRecord.TorqueLimit)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.SMOKE_LIMIT,        Conversions.fuelQuantityMg(logRecord.SmokeLimit)));
                        EventBus.getDefault().post(new DashboardEvent(DashboardEvent.DATA_TYPE.IDLE_DEMAND,        logRecord.IdleDemand        / 100.0));
                    }

                    csv_filehandle.println(String.format("%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d",
                            simpleDateFormat.format(new Date()),
                            logRecord.EngineRpm, logRecord.BatteryVoltage, logRecord.VehicleSpeed,
                            logRecord.CoolantTemperature, logRecord.ExternalTemperature, logRecord.InletTemperature, logRecord.FuelTemperature,
                            logRecord.AcceleratorTrack1, logRecord.AcceleratorTrack2, logRecord.AcceleratorTrack3, logRecord.AcceleratorSupply,
                            logRecord.AmbientPressure, logRecord.ManifoldAirPressure, logRecord.ManifoldAirFlow,
                            logRecord.DriverDemand, logRecord.MafAirMass, logRecord.MapAirMass, logRecord.InjectionQuantity,
                            logRecord.AfRatio, logRecord.TorqueLimit, logRecord.SmokeLimit, logRecord.IdleDemand
                    ));
                    csv_filehandle.flush();
                    if (pid_count == 0) {
                        Util.log_msg("Dashboard exiting because no PID's were read");
                        break;
                    }
                }
            }
            Util.log_msg("Dashboard stopped");
        } catch (Exception ex) {
            Util.log_msg(ex.toString());
        } finally {
            mDashboardRunning = false;
        }
    }

    private void read_faults() {
        EventBus.getDefault().post(new ToggleUIEvent(false));
        stopDashboard();

        if (mFastInitCompleted) {
            Util.log_msg("Reading fault ...");
            if (Consts.DEBUG_UI) {
                try { Thread.sleep(1200); } catch (Exception ex) {};
                byte[] demo = new byte[Faults.FIRST_BYTE + Faults.BYTE_COUNT];
                demo[Faults.FIRST_BYTE] = (byte) 0b01000000;
                demo[Faults.FIRST_BYTE + 6] = (byte) 0b00000100;
                EventBus.getDefault().post(new FaultsEvent(Faults.decode(demo)));
            } else if (get_pid(Requests.RequestPidEnum.FAULT_CODES)) {
                List<Faults.Fault> found = Faults.decode(mReadBuffer);
                for (Faults.Fault fault : found) {
                    Util.log_msg(String.format("%s %s", fault.code(), fault.label));
                }
                EventBus.getDefault().post(new FaultsEvent(found));
            }
            //Util.log_msg("Faults cleared");
        } else {
            Util.log_msg("You cannot read faults until FASTINIT has been successful!");
        }

        // Live data stops while the bus is busy; bring it back.
        startDashboard();
        EventBus.getDefault().post(new ToggleUIEvent(true));
    }

    private void clear_faults() {
        EventBus.getDefault().post(new ToggleUIEvent(false));
        stopDashboard();

        if (mFastInitCompleted) {
            Util.log_msg("Clearing fault ...");
            if (Consts.DEBUG_UI) {
                try { Thread.sleep(2000); } catch (Exception ex) {};
            } else {

                get_pid(Requests.RequestPidEnum.CLEAR_FAULTS);
            }
            Util.log_msg("Faults cleared");
        } else {
            Util.log_msg("You cannot clear faults until FASTINIT has been successful!");
        }

        // Live data stops while the bus is busy; bring it back.
        startDashboard();
        EventBus.getDefault().post(new ToggleUIEvent(true));
    }
/*
    private void pulserev() {
        EventBus.getDefault().post(new ToggleUIEvent(false));
        stopDashboard();

        if (mFastInitCompleted) {
            Util.log_msg("Test pulserev ...");
            if (Consts.DEBUG_UI) {
                try { Thread.sleep(2000); } catch (Exception ex) {};
            } else {

                get_pid(Requests.RequestPidEnum.GET_VIN);
                Util.log_msg("Get vin test");
            }
            Util.log_msg("Get vin test");
        } else {
            Util.log_msg("You cannot test until FASTINIT has been successful!");
        }

        EventBus.getDefault().post(new ToggleUIEvent(true));
    }
*/
    public void addInfoLine(String msg) {
        if (msg.length() > 0) {
            mInfoLines.add(msg) ;
        }
        if (mInfoLines.size() >= Consts.MAX_INFO_LINES) {
            mInfoLines.remove(0);
        }
        tvInfo.setText(TextUtils.join("\n", mInfoLines));
    }

    public void startDashboard() {
        if (mFastInitCompleted == false){
            Util.log_msg("You cannnot run the dashboard until FASTINIT has been successful!");
            return;
        } else {
            mDashboardRunning = true;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    dashboard();
                }
            });
            thread.start();
        }
    }

    private void stopDashboard() {
        Util.log_msg(String.format("Stopping dashboard ..."));
        mDashboardRunning = false;
        // FIXME: wait a while to be sure the dashboard has stopped
        try { Thread.sleep(2000); } catch (Exception ex) {};
    }


    @Override protected void bindViews(View view) {
        tvInfo = view.findViewById(R.id.tvInfo);
        tvStatus = view.findViewById(R.id.tvStatus);
        tvVin = view.findViewById(R.id.tvVin);
        tvFaults = view.findViewById(R.id.tvFaults);
        btConnect = view.findViewById(R.id.btConnect);
        btConnect.setOnClickListener(v -> connectHandler(v));
        btConnect.setOnLongClickListener(v -> {
            if (mUsbDeviceConnection != null || mFakeUsbDeviceConnection) return false;
            Consts.DEBUG_UI = !Consts.DEBUG_UI;
            Util.log_msg(Consts.DEBUG_UI
                    ? "Mode démo activé : les valeurs sont simulées."
                    : "Mode démo désactivé.");
            if (tvStatus != null) {
                tvStatus.setText(Consts.DEBUG_UI ? R.string.status_demo : R.string.status_disconnected);
            }
            return true;
        });
        view.findViewById(R.id.btReadFaults).setOnClickListener(v -> readFaultsHandler(v));
        view.findViewById(R.id.btClearFaults).setOnClickListener(v -> clearFaultsHandler(v));
    }

    // Demo mode: drives every gauge from a slow engine cycle, so the UI can be
    // worked on and shown without a vehicle attached.
    private double simulationPhase = 0;

    private void simulate() {
        simulationPhase += 0.04;
        double wave = (Math.sin(simulationPhase) + 1) / 2;          // 0..1
        double load = Math.pow(wave, 2);

        post(DashboardEvent.DATA_TYPE.RPM, 760 + load * 3400);
        post(DashboardEvent.DATA_TYPE.VEHICLE_SPEED, load * 110);
        post(DashboardEvent.DATA_TYPE.BATTERY_VOLTAGE, 13.9 + wave * 0.7);
        // The demo starts cold and warms up, so the warm-up strip can be seen.
        double warmup = Math.min(1.0, simulationPhase / 9.0);
        post(DashboardEvent.DATA_TYPE.COOLANT_TEMP, 22 + warmup * 64 + load * 6);
        post(DashboardEvent.DATA_TYPE.FUEL_TEMP, 62 + wave * 4);
        post(DashboardEvent.DATA_TYPE.INLET_TEMP, 27 + load * 35);
        post(DashboardEvent.DATA_TYPE.EXTERNAL_TEMP, 18 + wave * 2);
        post(DashboardEvent.DATA_TYPE.AMBIENT_PRESSURE, 99.2);
        post(DashboardEvent.DATA_TYPE.MANIFOLD_AIR_PRESSURE, 99.2 + load * 115);
        post(DashboardEvent.DATA_TYPE.AIR_FLOW, 57 + load * 620);
        post(DashboardEvent.DATA_TYPE.ACC_TRACK_1, 0.7 + load * 3.6);
        post(DashboardEvent.DATA_TYPE.ACC_TRACK_2, 0.7 + load * 3.6);
        post(DashboardEvent.DATA_TYPE.ACC_TRACK_3, 0.7 + load * 3.6);
        post(DashboardEvent.DATA_TYPE.ACC_SUPPLY, load * 10);
        post(DashboardEvent.DATA_TYPE.DRIVER_DEMAND, load * 48);
        post(DashboardEvent.DATA_TYPE.TORQUE_LIMIT, 40 + load * 12);
        post(DashboardEvent.DATA_TYPE.SMOKE_LIMIT, 29 + load * 18);
        post(DashboardEvent.DATA_TYPE.INJECTION_QUANTITY, 5 + load * 42);
        post(DashboardEvent.DATA_TYPE.IDLE_DEMAND, -4 + wave * 8);
        post(DashboardEvent.DATA_TYPE.MAF_AIR_MASS, 410 + load * 300);
        post(DashboardEvent.DATA_TYPE.MAP_AIR_MASS, 582 + load * 280);
        post(DashboardEvent.DATA_TYPE.AF_RATIO, 14.2 + (1 - load) * 7);
        post(DashboardEvent.DATA_TYPE.POWER_BALANCE_1, 2 + Math.sin(simulationPhase * 1.3));
        post(DashboardEvent.DATA_TYPE.POWER_BALANCE_2, 1 + Math.sin(simulationPhase * 1.7));
        post(DashboardEvent.DATA_TYPE.POWER_BALANCE_3, 1 + Math.sin(simulationPhase * 2.1));
        post(DashboardEvent.DATA_TYPE.POWER_BALANCE_4, Math.sin(simulationPhase * 0.9));
        post(DashboardEvent.DATA_TYPE.POWER_BALANCE_5, Math.sin(simulationPhase * 1.1));
    }

    private void post(DashboardEvent.DATA_TYPE type, double value) {
        EventBus.getDefault().post(new DashboardEvent(type, value));
    }

    // Identity is read once per connection; it never changes while the key is on.
    private void readVehicleInfo() {
        if (Consts.DEBUG_UI) {
            EventBus.getDefault().post(new VehicleInfoEvent(
                    "SALLDVBP7YA123456", "NNN500010", "MSB101090"));
            return;
        }
        String vin = readIdentity(Requests.RequestPidEnum.GET_VIN);
        String ecu = readIdentity(Requests.RequestPidEnum.GET_ECUTYPE);
        String map = readIdentity(Requests.RequestPidEnum.GET_MAPVARIANT);
        EventBus.getDefault().post(new VehicleInfoEvent(vin, ecu, map));
    }

    private String readIdentity(Requests.RequestPidEnum pid) {
        if (!get_pid(pid)) return "";
        int len = mRequests.request.get(pid).response_len & 0xFF;
        return Conversions.identityText(mReadBuffer, 3, Math.max(3, len - 1));
    }
}
