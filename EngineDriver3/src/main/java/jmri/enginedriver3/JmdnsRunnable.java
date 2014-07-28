package jmri.enginedriver3;

import android.net.wifi.WifiInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.net.Inet4Address;
import java.util.HashMap;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

/**
 * Created by stevet on 7/16/2014.
 */
class JmdnsRunnable implements Runnable {

    android.net.wifi.WifiManager.MulticastLock multicastLock;
    private PermaFragment permaFragment;
    private MainApplication mainApp;
    //        private Activity activity = null;  //this is now in the outer class
    //    private String jmdnsType = "_workstation._tcp.local.";
//    private String jmdnsType = "_http._tcp.local.";
    private String jmdnsType = "_withrottle._tcp.local.";
    private JmDNS jmdns = null;
    private ServiceListener listener = null;
    private ServiceInfo serviceInfo;
//    Handler jmdnsRunnableHandler;

    //create, expecting refs to permaFrag and mainApp passed in
    public JmdnsRunnable(PermaFragment in_permaFragment, MainApplication in_mainApp) {
        Log.d(Consts.DEBUG_TAG, "in JmdnsRunnable()");
        permaFragment = in_permaFragment;
        mainApp = in_mainApp;
    }

    @Override
    public void run() {
        Log.d(Consts.DEBUG_TAG, "starting JmdnsRunnable.run()");
        Looper.prepare();
        permaFragment.jmdnsRunnableHandler = new Jmdns_Handler();  //update ref to thread's handler back in retained frag
        startJmdns();
        Looper.loop();
        Log.d(Consts.DEBUG_TAG, "ending JmdnsRunnable.run()");
    }

    private void startJmdns() {
        Log.d(Consts.DEBUG_TAG, "Starting Jmdns listeners");
        android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) permaFragment.mainActivity.getSystemService(android.content.Context.WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock("engine_driver");
        multicastLock.setReferenceCounted(true);
        multicastLock.acquire();
        WifiInfo wifiinfo = wifi.getConnectionInfo();
        int intAddr = wifiinfo.getIpAddress();
        if (intAddr == 0) {  //must have a local address, else show error and don't start anything TODO: end the thread?
            Log.w(Consts.DEBUG_TAG, "No local IP address found!");
            mainApp.sendMsg(permaFragment.permaFragHandler, MessageType.MESSAGE_LONG, "ERROR: No local IP address found!");
        } else {
            byte[] byteAddr = new byte[]{(byte) (intAddr & 0xff), (byte) (intAddr >> 8 & 0xff), (byte) (intAddr >> 16 & 0xff),
                    (byte) (intAddr >> 24 & 0xff)};
            try {
                Inet4Address deviceAddr = (Inet4Address) Inet4Address.getByAddress(byteAddr);
                String deviceName = deviceAddr.toString().substring(1);        //strip off leading /
                Log.d(Consts.DEBUG_TAG, "startJmdns with local IP " + deviceName);
                jmdns = JmDNS.create(deviceAddr, deviceName);  //pass ip as name to avoid hostname lookup attempt
                jmdns.addServiceListener(jmdnsType, listener = new ServiceListener() {

                    @Override
                    public void serviceResolved(ServiceEvent ev) {
                        String additions = "";
                        if (ev.getInfo().getInetAddresses() != null && ev.getInfo().getInetAddresses().length > 0) {
                            additions = ev.getInfo().getInetAddresses()[0].getHostAddress();
                        }
                        Log.d(Consts.DEBUG_TAG, "Service resolved: " + ev.getInfo().getQualifiedName() + " port:" + ev.getInfo().getPort() + " " + additions);
                        int port=ev.getInfo().getPort();
                        String host_name = ev.getInfo().getName(); //
                        Inet4Address[] ip_addresses = ev.getInfo().getInet4Addresses();  //only get ipV4 address
                        String ip_address = ip_addresses[0].toString().substring(1);  //use first one, since WiThrottle is only putting one in (for now), and remove leading slash

                        boolean entryExists = false;
                        //stop if new address is already in the list
                        HashMap<String, String> tm;
                        for(int index=0; index < mainApp.discoveredServersList.size(); index++) {
                            tm = mainApp.discoveredServersList.get(index);
                            if (tm.get("ip_address").equals(ip_address)) {  //TODO: switch this back to host_name?  maybe?
                                entryExists = true;
                                break;
                            }
                        }
                        if(!entryExists) {                // if new host, add to global list and shout about it
                            HashMap<String, String> ds=new HashMap<String, String>();
                            ds.put("ip_address", ip_address);
                            ds.put("port", ((Integer) port).toString());
                            ds.put("host_name", host_name);
                            mainApp.discoveredServersList.add(ds);
                            mainApp.sendMsg(permaFragment.permaFragHandler, MessageType.DISCOVERED_SERVER_LIST_CHANGED);
                        }
                    }

                    @Override
                    public void serviceRemoved(ServiceEvent ev) {
                        Log.d(Consts.DEBUG_TAG, "Service removed: " + ev.getName());
                        //remove this name from the list (if found)
                        String host_name = ev.getInfo().getName();
                        HashMap<String, String> tm;
                        for(int index=0; index < mainApp.discoveredServersList.size(); index++) {
                            tm = mainApp.discoveredServersList.get(index);
                            if (tm.get("host_name").equals(host_name)) {
                                mainApp.discoveredServersList.remove(index);
                                break;
                            }
                        }
                        mainApp.sendMsg(permaFragment.permaFragHandler, MessageType.DISCOVERED_SERVER_LIST_CHANGED);
                    }

                    @Override
                    public void serviceAdded(ServiceEvent event) {
                        // Required to force serviceResolved to be called again (after the first search)
                        jmdns.requestServiceInfo(event.getType(), event.getName(), 1);
                    }
                });
//            serviceInfo = ServiceInfo.create("_test._tcp.local.", "AndroidTest", 0, "plain test service from android");
//            jmdns.registerService(serviceInfo);
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
        }
    }

    private void stopJmdns() {
        try {
            Log.d(Consts.DEBUG_TAG, "removing jmdns listener");
            jmdns.removeServiceListener(jmdnsType, listener);
            multicastLock.release();
        } catch (Exception e) {
            Log.d(Consts.DEBUG_TAG, "exception in jmdns.removeServiceListener()");
        }
        try {
            Log.d(Consts.DEBUG_TAG, "calling jmdns.close()");
            jmdns.close();
            Log.d(Consts.DEBUG_TAG, "after jmdns.close()");
        } catch (Exception e) {
            Log.d(Consts.DEBUG_TAG, "exception in jmdns.close()");
        }
        jmdns = null;
    }

    private class Jmdns_Handler extends Handler {

        @Override
        public void handleMessage(Message msg) {
//            Log.d(Consts.DEBUG_TAG, "in JmdnsRunnable.handleMessage()");
            switch (msg.what) {
                case MessageType.SHUTDOWN:
                    stopJmdns();
                    this.getLooper().quit(); //stop the looper
                    break;
                default:
                    Log.w(Consts.DEBUG_TAG, "in JmdnsRunnable.handleMessage() received unknown message type " + msg.what);
                    break;

            }  //end of switch msg.what
            super.handleMessage(msg);
        }
    }

}
