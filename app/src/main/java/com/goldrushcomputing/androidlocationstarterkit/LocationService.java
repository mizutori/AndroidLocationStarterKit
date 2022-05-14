package com.goldrushcomputing.androidlocationstarterkit;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.v4.content.LocalBroadcastManager;
import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;

import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import static android.content.ContentValues.TAG;

public class LocationService extends Service implements LocationListener, GpsStatus.Listener {
    public static final String LOG_TAG = LocationService.class.getSimpleName();

    private final LocationServiceBinder binder = new LocationServiceBinder();
    boolean isLocationManagerUpdatingLocation;



    ArrayList<Location> locationList;

    ArrayList<Location> oldLocationList;
    ArrayList<Location> noAccuracyLocationList;
    ArrayList<Location> inaccurateLocationList;
    ArrayList<Location> kalmanNGLocationList;


    boolean isLogging;

    float currentSpeed = 0.0f; // meters/second

    KalmanLatLong kalmanFilter;
    long runStartTimeInMillis;

    ArrayList<Integer> batteryLevelArray;
    ArrayList<Float> batteryLevelScaledArray;
    int batteryScale;
    int gpsCount;

    int goodGpsCount;

    public LocationService() {

    }

    @Override
    public void onCreate() {
        isLocationManagerUpdatingLocation = false;
        locationList = new ArrayList<>();
        noAccuracyLocationList = new ArrayList<>();
        oldLocationList = new ArrayList<>();
        inaccurateLocationList = new ArrayList<>();
        kalmanNGLocationList = new ArrayList<>();
        kalmanFilter = new KalmanLatLong(3);

        isLogging = false;

        batteryLevelArray = new ArrayList<>();
        batteryLevelScaledArray = new ArrayList<>();
        registerReceiver(this.batteryInfoReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }



    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        super.onStartCommand(i, flags, startId);
        return Service.START_STICKY;
    }


    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }


    @Override
    public void onRebind(Intent intent) {
        Log.d(LOG_TAG, "onRebind ");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(LOG_TAG, "onUnbind ");

        return true;
    }

    @Override
    public void onDestroy() {
        Log.d(LOG_TAG, "onDestroy ");


    }


    //This is where we detect the app is being killed, thus stop service.
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(LOG_TAG, "onTaskRemoved ");
        this.stopUpdatingLocation();

        stopSelf();
    }




    /**
     * Binder class
     *
     * @author Takamitsu Mizutori
     *
     */
    public class LocationServiceBinder extends Binder {
        public LocationService getService() {
            return LocationService.this;
        }
    }



    /* LocationListener implemenation */
    @Override
    public void onProviderDisabled(String provider) {
        if (provider.equals(LocationManager.GPS_PROVIDER)) {
            notifyLocationProviderStatusUpdated(false);
        }

    }

    @Override
    public void onProviderEnabled(String provider) {
        if (provider.equals(LocationManager.GPS_PROVIDER)) {
            notifyLocationProviderStatusUpdated(true);
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        if (provider.equals(LocationManager.GPS_PROVIDER)) {
            if (status == LocationProvider.OUT_OF_SERVICE) {
                notifyLocationProviderStatusUpdated(false);
            } else {
                notifyLocationProviderStatusUpdated(true);
            }
        }
    }

    /* GpsStatus.Listener implementation */
    public void onGpsStatusChanged(int event) {


    }

    private void notifyLocationProviderStatusUpdated(boolean isLocationProviderAvailable) {
        //Broadcast location provider status change here
    }

    public void startLogging(){
        isLogging = true;
    }

    public void stopLogging(){
        if (locationList.size() > 1 && batteryLevelArray.size() > 1){
            long currentTimeInMillis = (long)(SystemClock.elapsedRealtimeNanos() / 1000000);
            long elapsedTimeInSeconds = (currentTimeInMillis - runStartTimeInMillis) / 1000;
            float totalDistanceInMeters = 0;
            for(int i = 0; i < locationList.size() - 1; i++){
                totalDistanceInMeters +=  locationList.get(i).distanceTo(locationList.get(i + 1));
            }
            int batteryLevelStart = batteryLevelArray.get(0).intValue();
            int batteryLevelEnd = batteryLevelArray.get(batteryLevelArray.size() - 1).intValue();

            float batteryLevelScaledStart = batteryLevelScaledArray.get(0).floatValue();
            float batteryLevelScaledEnd = batteryLevelScaledArray.get(batteryLevelScaledArray.size() - 1).floatValue();

            saveLog(elapsedTimeInSeconds, totalDistanceInMeters, gpsCount, batteryLevelStart, batteryLevelEnd, batteryLevelScaledStart, batteryLevelScaledEnd);
        }
        isLogging = false;
    }



    public void startUpdatingLocation() {
        if(this.isLocationManagerUpdatingLocation == false){
            isLocationManagerUpdatingLocation = true;
            runStartTimeInMillis = (long)(SystemClock.elapsedRealtimeNanos() / 1000000);


            locationList.clear();

            oldLocationList.clear();
            noAccuracyLocationList.clear();
            inaccurateLocationList.clear();
            kalmanNGLocationList.clear();

            LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

            //Exception thrown when GPS or Network provider were not available on the user's device.
            try {
                Criteria criteria = new Criteria();
                criteria.setAccuracy(Criteria.ACCURACY_FINE); //setAccuracyは内部では、https://stackoverflow.com/a/17874592/1709287の用にHorizontalAccuracyの設定に変換されている。
                    criteria.setPowerRequirement(Criteria.POWER_HIGH);
                criteria.setAltitudeRequired(false);
                criteria.setSpeedRequired(true);
                criteria.setCostAllowed(true);
                criteria.setBearingRequired(false);

                //API level 9 and up
                criteria.setHorizontalAccuracy(Criteria.ACCURACY_HIGH);
                criteria.setVerticalAccuracy(Criteria.ACCURACY_HIGH);
                //criteria.setBearingAccuracy(Criteria.ACCURACY_HIGH);
                //criteria.setSpeedAccuracy(Criteria.ACCURACY_HIGH);

                Integer gpsFreqInMillis = 5000;
                Integer gpsFreqInDistance = 5;  // in meters

                locationManager.addGpsStatusListener(this);

                locationManager.requestLocationUpdates(gpsFreqInMillis, gpsFreqInDistance, criteria, this, null);

                /* Battery Consumption Measurement */
                gpsCount = 0;
                batteryLevelArray.clear();
                batteryLevelScaledArray.clear();

                goodGpsCount = 0;

            } catch (IllegalArgumentException e) {
                Log.e(LOG_TAG, e.getLocalizedMessage());
            } catch (SecurityException e) {
                Log.e(LOG_TAG, e.getLocalizedMessage());
            } catch (RuntimeException e) {
                Log.e(LOG_TAG, e.getLocalizedMessage());
            }
        }
    }


    public void stopUpdatingLocation(){
        if(this.isLocationManagerUpdatingLocation == true){
            LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            locationManager.removeUpdates(this);
            isLocationManagerUpdatingLocation = false;
        }
    }

    @Override
    public void onLocationChanged(final Location newLocation) {
        Log.d(TAG, "(" + newLocation.getLatitude() + "," + newLocation.getLongitude() + ")");

        gpsCount++;

        Location filtered = filterLocation(newLocation);

        if(isLogging){
            if(filtered != null){
                currentSpeed = filtered.getSpeed();
                locationList.add(filtered);
            }
        }else{
            // if newLocation passed the filter, count up goodLocationCount.
            if(filtered != null){
                goodGpsCount++;
                if(goodGpsCount > 2){
                    Intent intent = new Intent("GotEnoughLocations");
                    intent.putExtra("goodLocationCount", goodGpsCount);
                    LocalBroadcastManager.getInstance(this.getApplication()).sendBroadcast(intent);
                }
            }
        }

        Intent intent = new Intent("LocationUpdated");
        intent.putExtra("location", newLocation);

        LocalBroadcastManager.getInstance(this.getApplication()).sendBroadcast(intent);
    }

    @SuppressLint("NewApi")
    private long getLocationAge(Location newLocation){
        long locationAge;
        if(android.os.Build.VERSION.SDK_INT >= 17) {
            long currentTimeInMilli = (long)(SystemClock.elapsedRealtimeNanos() / 1000000);
            long locationTimeInMilli = (long)(newLocation.getElapsedRealtimeNanos() / 1000000);
            locationAge = currentTimeInMilli - locationTimeInMilli;
        }else{
            locationAge = System.currentTimeMillis() - newLocation.getTime();
        }
        return locationAge;
    }


    private Location filterLocation(Location location){

        long age = getLocationAge(location);

        if(age > 5 * 1000){ //more than 5 seconds
            Log.d(TAG, "Location is old");
            oldLocationList.add(location);
            return null;
        }

        if(location.getAccuracy() <= 0){
            Log.d(TAG, "Latitidue and longitude values are invalid.");
            noAccuracyLocationList.add(location);
            return null;
        }

        //setAccuracy(newLocation.getAccuracy());
        float horizontalAccuracy = location.getAccuracy();
        if(horizontalAccuracy > 10){ //10meter filter
            Log.d(TAG, "Accuracy is too low.");
            inaccurateLocationList.add(location);
            return null;
        }

        Log.d(TAG, "Location quality is good enough.");
        return location;
    }



    /* Battery Consumption */
    private BroadcastReceiver batteryInfoReceiver = new BroadcastReceiver(){
        @Override
        public void onReceive(Context ctxt, Intent intent) {
            int batteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

            float batteryLevelScaled = batteryLevel / (float)scale;



            batteryLevelArray.add(Integer.valueOf(batteryLevel));
            batteryLevelScaledArray.add(Float.valueOf(batteryLevelScaled));
            batteryScale = scale;
        }
    };

    /* Data Logging */
    public synchronized void saveLog(long timeInSeconds, double distanceInMeters, int gpsCount, int batteryLevelStart, int batteryLevelEnd, float batteryLevelScaledStart, float batteryLevelScaledEnd) {
        SimpleDateFormat fileNameDateTimeFormat = new SimpleDateFormat("yyyy_MMdd_HHmm");
        String filePath = this.getExternalFilesDir(null).getAbsolutePath() + "/"
                + fileNameDateTimeFormat.format(new Date()) + "_battery" + ".csv";

        Log.d(TAG, "saving to " + filePath);

        FileWriter fileWriter = null;
        try {
            fileWriter = new FileWriter(filePath, false);
            fileWriter.append("Time,Distance,GPSCount,BatteryLevelStart,BatteryLevelEnd,BatteryLevelStart(/" + batteryScale + ")," + "BatteryLevelEnd(/" + batteryScale + ")" + "\n");
            String record = "" + timeInSeconds + ',' + distanceInMeters + ',' + gpsCount + ',' + batteryLevelStart + ',' + batteryLevelEnd + ',' + batteryLevelScaledStart + ',' + batteryLevelScaledEnd + '\n';
            fileWriter.append(record);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (fileWriter != null) {
                try {
                    fileWriter.close();
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                }
            }
        }
    }



}


