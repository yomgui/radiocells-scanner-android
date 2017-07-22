/*
	Radiobeacon - Openbmap wifi and cell logger
    Copyright (C) 2013  wish7

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation, either version 3 of the
    License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.openbmap.services.wireless;

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteCantOpenDatabaseException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Messenger;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.CellLocation;
import android.telephony.NeighboringCellInfo;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.openbmap.Constants;
import org.openbmap.Preferences;
import org.openbmap.db.DataHelper;
import org.openbmap.db.models.CellRecord;
import org.openbmap.db.models.MetaData;
import org.openbmap.db.models.PositionRecord;
import org.openbmap.db.models.WifiRecord.CatalogStatus;
import org.openbmap.events.onBlacklisted;
import org.openbmap.events.onCellChanged;
import org.openbmap.events.onCellSaved;
import org.openbmap.events.onCellScannerStart;
import org.openbmap.events.onCellScannerStop;
import org.openbmap.events.onLocationUpdated;
import org.openbmap.services.ManagerService;
import org.openbmap.services.wireless.blacklists.BlacklistReasonType;
import org.openbmap.services.wireless.blacklists.LocationBlackList;
import org.openbmap.utils.CellValidator;
import org.openbmap.utils.GeometryUtils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static android.os.Build.VERSION.SDK_INT;
import static org.openbmap.utils.CellValidator.isValidCell;

/**
 * CellScannerService takes care of cell logging
 */
public class CellScannerService extends Service implements ActivityCompat.OnRequestPermissionsResultCallback {

    public static final String TAG = CellScannerService.class.getSimpleName();

    /**
     * Keeps the SharedPreferences
     */
    private SharedPreferences prefs = null;


    /**
     * Current session id
     */
    private long session = Constants.SESSION_NOT_TRACKING;

    /*
     * minimum time interval between two cells update in milliseconds
     */
    // TODO: move to preferences
    protected static final long MIN_CELL_TIME_INTERVAL = 2000;

    /**
     * in demo mode cells are recorded continuously, regardless of minimum distance set
     * ALWAYS set DEMO_MODE to false in production release
     */
    protected static final boolean DEMO_MODE = false;

    /**
     * Phone state listeners to receive cell updates
     */
    private TelephonyManager telephonyManager;

    private PhoneStateListener phoneStateListener;

    /*
     * 	Cells Strength information
     */
    private static int INVALID_STRENGTH = -1;
    private int gsmStrengthDbm = INVALID_STRENGTH;
    private int gsmStrengthAsu = INVALID_STRENGTH;
    private int cdmaStrengthDbm = INVALID_STRENGTH;
    private int cdmaEcIo = INVALID_STRENGTH;
    private int signalStrengthEvdodBm = INVALID_STRENGTH;
    private int signalStrengthEvdoEcio = INVALID_STRENGTH;
    private int signalStrengthSnr = INVALID_STRENGTH;
    private int gsmBitErrorRate = INVALID_STRENGTH;
    private final int signalStrengthGsm = INVALID_STRENGTH;
    private final boolean signalStrengthIsGsm = false;

    /*
     * last known location
     */
    private Location lastLocation = new Location("DUMMY");
    private String lastLocationProvider;

    /*
     * location of last saved cell
     */
    private Location savedAt = new Location("DUMMY");

    /*
     * Position where wifi scan has been initiated.
     */
    private Location startScanLocation;

    /**
     * Location provider's name (e.g. GPS)
     */
    private String startScanLocationProvider;

    /**
     * Are we currently tracking ?
     */
    private boolean isTracking = false;

    /**
     * Current serving cell
     */
    private CellInfo currentCell;


    /*
     * DataHelper for persisting recorded information in database
     */
    private DataHelper dataHelper;


    /**
     * List of blocked areas, e.g. home zone
     */
    private LocationBlackList locationBlacklist;

    /**
     * Radiocells catalog database (used for checking if new wifi)
     */
    private SQLiteDatabase wifiCatalog;


    @Override
    public final void onCreate() {
        Log.d(TAG, "CellScannerService created");
        super.onCreate();

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this);
        } else {
            Log.w(TAG, "Event bus receiver already registered");
        }

        // Set savedAt and mWifiSavedAt to default values (Lat 0, Lon 0)
        // Thus MIN_CELL_DISTANCE filters are always fired on startup
        savedAt = new Location("DUMMY");

        // get shared preferences
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (!prefs.getString(Preferences.KEY_CATALOG_FILE, Preferences.DEFAULT_CATALOG_FILE).equals(Preferences.VAL_CATALOG_NONE)) {
            final String catalogPath = prefs.getString(Preferences.KEY_WIFI_CATALOG_FOLDER,
                    getApplicationContext().getExternalFilesDir(null).getAbsolutePath() + File.separator + Preferences.CATALOG_SUBDIR)
                    + File.separator + prefs.getString(Preferences.KEY_CATALOG_FILE, Preferences.DEFAULT_CATALOG_FILE);

            if (!(new File(catalogPath)).exists()) {
                Log.w(TAG, "Selected catalog doesn't exist");
                wifiCatalog = null;
            } else {
                try {
                    wifiCatalog = SQLiteDatabase.openDatabase(catalogPath, null, SQLiteDatabase.OPEN_READONLY);
                } catch (final SQLiteCantOpenDatabaseException ex) {
                    Log.e(TAG, "Can't open wifi catalog database @ " + catalogPath);
                    wifiCatalog = null;
                }
            }
        } else {
            Log.w(TAG, "No wifi catalog selected. Can't compare scan results with openbmap dataset.");
        }

		/*
         * Setting up database connection
		 */
        dataHelper = new DataHelper(this);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            registerPhoneStateManager();
        } else {
            Log.w(TAG, "PhoneStateManager requires ACCESS_COARSE_LOCATION permission - can't register");
            return;
        }


        initBlacklists();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        boolean isGranted = false;
        for (int i = 0; i < grantResults.length; i++)
            if (permissions[i].equals(Manifest.permission.ACCESS_FINE_LOCATION) && (grantResults[i] == PackageManager.PERMISSION_GRANTED))
                isGranted = true;
        if (isGranted) {
            registerPhoneStateManager();
        }
        else {
            Log.w(TAG, "ACCESS_COARSE_LOCATION permission denied - Can't start scanning");
        }
    }
    /**
     * Setup SSID / location blacklists
     */
    private void initBlacklists() {

        final String path = getApplicationContext().getExternalFilesDir(null).getAbsolutePath()
                + File.separator
                + Preferences.BLACKLIST_SUBDIR;

        locationBlacklist = new LocationBlackList();
        locationBlacklist.openFile(path
                + File.separator
                + Constants.DEFAULT_LOCATION_BLOCK_FILE);
    }

    /**
     * Register phone state listener for permanently measuring cell signal strength
     */
    private void registerPhoneStateManager() {
        Log.i(TAG, "Booting telephony manager");
        telephonyManager = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);

        printDebugInfos();

        phoneStateListener = new PhoneStateListener() {
            /**
             * Save signal strength changes in real-time
             * @param signalStrength
             */
            @Override
            public void onSignalStrengthsChanged(final SignalStrength signalStrength) {
                // TODO we need a timestamp for signal strength
                try {
                    cdmaStrengthDbm = signalStrength.getCdmaDbm();
                    cdmaEcIo = signalStrength.getCdmaEcio();
                } catch (final Exception e) {
                    Log.e(TAG, e.toString(), e);
                }
                try {
                    signalStrengthEvdodBm = signalStrength.getEvdoDbm();
                    signalStrengthEvdoEcio = signalStrength.getEvdoEcio();
                    signalStrengthSnr = signalStrength.getEvdoSnr();
                } catch (final Exception e) {
                    Log.e(TAG, e.toString(), e);
                }

                try {
                    gsmBitErrorRate = signalStrength.getGsmBitErrorRate();
                    gsmStrengthAsu = signalStrength.getGsmSignalStrength();
                    gsmStrengthDbm = -113 + 2 * gsmStrengthAsu; // conversion ASU in dBm
                } catch (final Exception e) {
                    Log.e(TAG, e.toString(), e);
                }
            }

            @Override
            public void onServiceStateChanged(final ServiceState serviceState) {
                switch (serviceState.getState()) {
                    case ServiceState.STATE_POWER_OFF:
                        try {
                            Log.i(TAG, "Service state: power off");
                            final SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");
                            final Date now = new Date();
                            final String date = formatter.format(now);
                        } catch (final Exception e) {
                            Log.e(TAG, e.toString(), e);
                        }
                        break;
                    case ServiceState.STATE_OUT_OF_SERVICE:
                        try {
                            Log.i(TAG, "Service state: out of service");
                            final SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");
                            final Date now = new Date();
                            final String date = formatter.format(now);
                        } catch (final Exception e) {
                            Log.e(TAG, e.toString(), e);
                        }
                        break;
                    default:
                }
            }

            @Override
            public void onCellInfoChanged(List<CellInfo> cellInfo) {
                Log.d(TAG, "onCellInfoChanged fired");
                if (cellInfo != null && cellInfo.size() > 0) {
                    CellInfo first = cellInfo.get(0);
                    if (!first.equals(currentCell)) {
                        Log.v(TAG, "Cell info changed: " + first.toString());
                        EventBus.getDefault().post(new onCellChanged(first));
                    }
                    currentCell = first;
                }
                super.onCellInfoChanged(cellInfo);
            }

            @Override
            public void onCellLocationChanged(CellLocation location) {
                Log.d(TAG, "onCellLocationChanged fired");
                EventBus.getDefault().post(new onCellChanged(null));
                super.onCellLocationChanged(location);
            }
        };

        /**
         *	Register TelephonyManager updates
         */
        telephonyManager.listen(phoneStateListener,
                PhoneStateListener.LISTEN_SERVICE_STATE
                        | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                        | PhoneStateListener.LISTEN_CELL_LOCATION);
    }

    /**
     * Outputs some debug infos to catlog
     */
    private void printDebugInfos() {
        if (SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            final PackageManager pm = getApplicationContext().getPackageManager();
            //Log.i(TAG, telephonyManager.getPhoneCount() == 1 ? "Single SIM mode" : "Dual SIM mode");
            Log.wtf(TAG, "------------ YOU MAY WANT TO REDACT INFO BELOW BEFORE POSTING THIS TO THE INTERNET ------------ ");
            Log.i(TAG, "GPS support: " + pm.hasSystemFeature("android.hardware.location.gps"));
            Log.i(TAG, "GSM support: " + pm.hasSystemFeature("android.hardware.telephony.gsm"));
            Log.i(TAG, "Wifi support: " + pm.hasSystemFeature("android.hardware.wifi"));

            Log.i(TAG, "SIM operator: " + telephonyManager.getSimOperator());
            Log.i(TAG, telephonyManager.getSimOperatorName());
            Log.i(TAG, "Network operator: " + telephonyManager.getNetworkOperator());
            Log.i(TAG, telephonyManager.getNetworkOperatorName());
            Log.i(TAG, "Roaming: " + telephonyManager.isNetworkRoaming());
            Log.wtf(TAG, "------------ YOU MAY WANT TO REDACT INFO BELOW ABOVE POSTING THIS TO THE INTERNET ------------ ");
        }
    }

    /**
     * Unregisters PhoneStateListener
     */
    private void unregisterPhoneStateManager() {
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
    }


    @Override
    public final void onDestroy() {
        Log.d(TAG, "Destroying CellScannerService");
        if (isTracking) {
            stopTracking();
            unregisterPhoneStateManager();
        }

        if (wifiCatalog != null && wifiCatalog.isOpen()) {
            wifiCatalog.close();
        }

        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }

        super.onDestroy();
    }

    /**
     * Target we publish for clients to send messages to IncomingHandler.
     */
    final Messenger upstreamMessenger = new Messenger(new ManagerService.UpstreamHandler());

    /**
     * When binding to the service, we return an interface to our messenger
     * for sending messages to the service.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return upstreamMessenger.getBinder();
    }


    /**
     * Broadcasts ignore message
     *
     * @param because reason (location / bssid / ssid
     * @param message additional info
     */
    private void broadcastBlacklisted(final BlacklistReasonType because,
                                      final String message) {
        EventBus.getDefault().post(new onBlacklisted(because, message));
    }


    /**
     * Processes cell related information and saves them to database
     *
     * @param here         current location
     * @param providerName Name of the position provider (e.g. gps)
     * @return true if at least one cell has been saved
     */
    private boolean updateCells(final Location here, final String providerName) {
        // Is cell tracking disabled?
        if (!prefs.getBoolean(Preferences.KEY_LOG_CELLS, Preferences.DEFAULT_SAVE_CELLS)) {
            Log.i(TAG, "Didn't save cells: cells tracking is disabled.");
            return false;
        }

        // Do we have gps?
        if (!GeometryUtils.isValidLocation(here)) {
            Log.e(TAG, "GPS location invalid (null or default value)");
            return false;
        }

        // if we're in blocked area, skip everything
        // set savedAt nevertheless, so next scan can be scheduled properly
        if (locationBlacklist.contains(here)) {
            Log.i(TAG, "Didn't save cells: location blacklisted");
            savedAt = here;
            return false;
        }

        final ArrayList<CellRecord> cells = new ArrayList<>();
        // Common data across all cells from scan:
        // 		All cells share same position
        // 		Neighbor cells share mnc, mcc and operator with serving cell
        final PositionRecord pos = new PositionRecord(here, session, providerName);
        final List<CellInfo> cellInfoList = telephonyManager.getAllCellInfo();

        if (cellInfoList != null && cellInfoList.size() > 0) {
            Log.v(TAG, "Found " + cellInfoList.size() + " cells");
            for (final CellInfo info : cellInfoList) {
                final CellRecord cell = serializeCell(info, pos);
                if (cell != null) {
                    cells.add(cell);
                }
            }
            // now persist list of cell records in database
            // Caution: So far we set end position = begin position
            dataHelper.saveCellsScanResults(cells, pos, pos);
            currentCell = cellInfoList.get(0);

            if (cells.size() > 0) {
                broadcastCellInfo(cells.get(0));
            }

            return (cells.size() > 0);
        } else {
            // crappy SAMSUNG devices don't implement getAllCellInfo(), so try alternative solution
            Log.v(TAG, "Collecting cell infos (API <= 17)");
            final CellLocation fallback = telephonyManager.getCellLocation();
            if (fallback instanceof GsmCellLocation) {
                final CellRecord serving = serializeCellLegacy(fallback, pos);
                if (serving != null) {
                    cells.add(serving);
                }

                final ArrayList<CellRecord> neigbors = processNeighbors(serving, pos);
                cells.addAll(neigbors);
                dataHelper.saveCellsScanResults(cells, pos, pos);

                if (serving != null) {
                    broadcastCellInfo(serving);
                }
                return true;
            }
            return false;
        }

    }

    /**
     * Create a {@link CellRecord} by parsing {@link CellInfo}
     *
     * @param cell     {@linkplain CellInfo}
     * @param position {@linkplain PositionRecord Current position}
     * @return {@link CellRecord}
     */
    private CellRecord serializeCell(final CellInfo cell, final PositionRecord position) {
        if (cell instanceof CellInfoGsm) {
            /*
			 * In case of GSM network set GSM specific values
			 */
            final CellIdentityGsm gsmIdentity = ((CellInfoGsm) cell).getCellIdentity();

            if (isValidCell(gsmIdentity)) {
                Log.i(TAG, "Processing gsm cell " + gsmIdentity.getCid());
                final CellRecord result = new CellRecord(session);
                result.setIsCdma(false);

                // generic cell info
                result.setNetworkType(telephonyManager.getNetworkType());
                // TODO: unelegant: implicit conversion from UTC to YYYYMMDDHHMMSS in begin.setTimestamp
                result.setOpenBmapTimestamp(position.getOpenBmapTimestamp());
                result.setBeginPosition(position);
                // so far we set end position = begin position
                result.setEndPosition(position);
                result.setIsServing(true);

                result.setIsNeighbor(!cell.isRegistered());

                // GSM specific
                result.setLogicalCellId(gsmIdentity.getCid());

                // add UTRAN ids, if needed
                if (gsmIdentity.getCid() > 0xFFFFFF) {
                    result.setUtranRnc(gsmIdentity.getCid() >> 16);
                    result.setActualCid(gsmIdentity.getCid() & 0xFFFF);
                } else {
                    result.setActualCid(gsmIdentity.getCid());
                }

                // at least for Nexus 4, even HSDPA networks broadcast psc
                result.setPsc(gsmIdentity.getPsc());

                final String operator = telephonyManager.getNetworkOperator();
                // getNetworkOperator() may return empty string, probably due to dropped connection
                if (operator.length() > 3) {
                    result.setOperator(operator);
                    result.setMcc(operator.substring(0, 3));
                    result.setMnc(operator.substring(3));
                } else {
                    Log.e(TAG, "Couldn't determine network operator, skipping cell");
                    return null;
                }

                final String networkOperatorName = telephonyManager.getNetworkOperatorName();
                if (networkOperatorName != null) {
                    result.setOperatorName(telephonyManager.getNetworkOperatorName());
                } else {
                    Log.e(TAG, "Error retrieving network operator's name, skipping cell");
                    return null;
                }

                result.setArea(gsmIdentity.getLac());
                result.setStrengthdBm(((CellInfoGsm) cell).getCellSignalStrength().getDbm());
                result.setStrengthAsu(((CellInfoGsm) cell).getCellSignalStrength().getAsuLevel());

                return result;
            }
        } else if (cell instanceof CellInfoWcdma) {
            final CellIdentityWcdma wcdmaIdentity = ((CellInfoWcdma) cell).getCellIdentity();
            if (isValidCell(wcdmaIdentity)) {
                Log.i(TAG, "Processing wcdma cell " + wcdmaIdentity.getCid());
                final CellRecord result = new CellRecord(session);
                result.setIsCdma(false);

                // generic cell info
                result.setNetworkType(telephonyManager.getNetworkType());
                // TODO: unelegant: implicit conversion from UTC to YYYYMMDDHHMMSS in begin.setTimestamp
                result.setOpenBmapTimestamp(position.getOpenBmapTimestamp());
                result.setBeginPosition(position);
                // so far we set end position = begin position
                result.setEndPosition(position);
                result.setIsServing(true);

                result.setIsNeighbor(!cell.isRegistered());

                result.setLogicalCellId(wcdmaIdentity.getCid());

                // add UTRAN ids, if needed
                if (wcdmaIdentity.getCid() > 0xFFFFFF) {
                    result.setUtranRnc(wcdmaIdentity.getCid() >> 16);
                    result.setActualCid(wcdmaIdentity.getCid() & 0xFFFF);
                } else {
                    result.setActualCid(wcdmaIdentity.getCid());
                }

                if (SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
                    // at least for Nexus 4, even HSDPA networks broadcast psc
                    result.setPsc(wcdmaIdentity.getPsc());
                }

                final String operator = telephonyManager.getNetworkOperator();
                // getNetworkOperator() may return empty string, probably due to dropped connection
                if (operator.length() > 3) {
                    result.setOperator(operator);
                    result.setMcc(operator.substring(0, 3));
                    result.setMnc(operator.substring(3));
                } else {
                    Log.e(TAG, "Couldn't determine network operator, skipping cell");
                    return null;
                }

                final String networkOperatorName = telephonyManager.getNetworkOperatorName();
                if (networkOperatorName != null) {
                    result.setOperatorName(telephonyManager.getNetworkOperatorName());
                } else {
                    Log.e(TAG, "Error retrieving network operator's name, skipping cell");
                    return null;
                }

                result.setArea(wcdmaIdentity.getLac());
                result.setStrengthdBm(((CellInfoWcdma) cell).getCellSignalStrength().getDbm());
                result.setStrengthAsu(((CellInfoWcdma) cell).getCellSignalStrength().getAsuLevel());

                return result;
            }
        } else if (cell instanceof CellInfoCdma) {
            final CellIdentityCdma cdmaIdentity = ((CellInfoCdma) cell).getCellIdentity();
            if (isValidCell(cdmaIdentity)) {
				/*
				 * In case of CDMA network set CDMA specific values
				 * Assume CDMA network, if cdma location and basestation, network and system id are available
				 */
                Log.i(TAG, "Processing cdma cell " + cdmaIdentity.getBasestationId());
                final CellRecord result = new CellRecord(session);
                result.setIsCdma(true);

                // generic cell info
                result.setNetworkType(telephonyManager.getNetworkType());
                // TODO: unelegant: implicit conversion from UTC to YYYYMMDDHHMMSS in begin.setTimestamp
                result.setOpenBmapTimestamp(position.getOpenBmapTimestamp());
                result.setBeginPosition(position);
                // so far we set end position = begin position
                result.setEndPosition(position);
                result.setIsServing(true);
                result.setIsNeighbor(false);

                // getNetworkOperator can be unreliable in CDMA networks, thus be careful
                // {@link http://developer.android.com/reference/android/telephony/TelephonyManager.html#getNetworkOperator()}
                final String operator = telephonyManager.getNetworkOperator();
                if (operator.length() > 3) {
                    result.setOperator(operator);
                    result.setMcc(operator.substring(0, 3));
                    result.setMnc(operator.substring(3));
                } else {
                    Log.i(TAG, "Couldn't determine network operator, this might happen in CDMA network");
                    result.setMcc("");
                    result.setMnc("");
                }

                final String networkOperatorName = telephonyManager.getNetworkOperatorName();
                if (networkOperatorName != null) {
                    result.setOperatorName(telephonyManager.getNetworkOperatorName());
                } else {
                    Log.i(TAG, "Error retrieving network operator's name, this might happen in CDMA network");
                    result.setOperatorName("");
                }

                // CDMA specific
                result.setBaseId(String.valueOf(cdmaIdentity.getBasestationId()));
                result.setNetworkId(String.valueOf(cdmaIdentity.getNetworkId()));
                result.setSystemId(String.valueOf(cdmaIdentity.getSystemId()));

                result.setStrengthdBm(((CellInfoCdma) cell).getCellSignalStrength().getCdmaDbm());
                result.setStrengthAsu(((CellInfoCdma) cell).getCellSignalStrength().getAsuLevel());
                return result;
            }
        } else if (cell instanceof CellInfoLte) {
            final CellIdentityLte lteIdentity = ((CellInfoLte) cell).getCellIdentity();
            if (isValidCell(lteIdentity)) {
                Log.i(TAG, "Processing LTE cell " + lteIdentity.getCi());
                final CellRecord result = new CellRecord(session);
                result.setIsCdma(false);

                // generic cell info
                result.setNetworkType(telephonyManager.getNetworkType());
                // TODO: unelegant: implicit conversion from UTC to YYYYMMDDHHMMSS in begin.setTimestamp
                result.setOpenBmapTimestamp(position.getOpenBmapTimestamp());
                result.setBeginPosition(position);
                // so far we set end position = begin position
                result.setEndPosition(position);
                result.setIsServing(true);

                result.setIsNeighbor(!cell.isRegistered());
                result.setLogicalCellId(lteIdentity.getCi());

                // set Actual Cid = Logical Cid (as we don't know better at the moment)
                result.setActualCid(result.getLogicalCellId());

                final String operator = telephonyManager.getNetworkOperator();
                // getNetworkOperator() may return empty string, probably due to dropped connection
                if (operator.length() > 3) {
                    result.setOperator(operator);
                    result.setMcc(operator.substring(0, 3));
                    result.setMnc(operator.substring(3));
                } else {
                    Log.e(TAG, "Couldn't determine network operator, skipping cell");
                    return null;
                }

                final String networkOperatorName = telephonyManager.getNetworkOperatorName();
                if (networkOperatorName != null) {
                    result.setOperatorName(telephonyManager.getNetworkOperatorName());
                } else {
                    Log.e(TAG, "Error retrieving network operator's name, skipping cell");
                    return null;
                }

                // LTE specific
                result.setArea(lteIdentity.getTac());
                result.setPsc(lteIdentity.getPci());

                result.setStrengthdBm(((CellInfoLte) cell).getCellSignalStrength().getDbm());
                result.setStrengthAsu(((CellInfoLte) cell).getCellSignalStrength().getAsuLevel());
                return result;
            }
        }
        return null;
    }

    /**
     * Legacy mode: use only if you can't get cell infos via telephonyManager.getAllCellInfo()
     *
     * @param cell
     * @param position
     * @return
     */
    @Deprecated
    private CellRecord serializeCellLegacy(final CellLocation cell, final PositionRecord position) {
        if (cell instanceof GsmCellLocation) {
            /*
			 * In case of GSM network set GSM specific values
			 */
            final GsmCellLocation gsmLocation = (GsmCellLocation) cell;

            if (isValidCell(gsmLocation)) {
                Log.i(TAG, "Assuming gsm (assumption based on cell-id" + gsmLocation.getCid() + ")");
                final CellRecord serving = processGsm(position, gsmLocation);

                if (serving == null) {
                    return null;
                }

                return serving;
            }
        }
        return null;
    }

    private CellRecord processGsm(PositionRecord position, GsmCellLocation gsmLocation) {
        final CellRecord serving = new CellRecord(session);
        serving.setIsCdma(false);

        // generic cell info
        serving.setNetworkType(telephonyManager.getNetworkType());
        // TODO: unelegant: implicit conversion from UTC to YYYYMMDDHHMMSS in begin.setTimestamp
        serving.setOpenBmapTimestamp(position.getOpenBmapTimestamp());
        serving.setBeginPosition(position);
        // so far we set end position = begin position
        serving.setEndPosition(position);
        serving.setIsServing(true);
        serving.setIsNeighbor(false);

        // GSM specific
        serving.setLogicalCellId(gsmLocation.getCid());

        // add UTRAN ids, if needed
        if (gsmLocation.getCid() > 0xFFFFFF) {
            serving.setUtranRnc(gsmLocation.getCid() >> 16);
            serving.setActualCid(gsmLocation.getCid() & 0xFFFF);
        } else {
            serving.setActualCid(gsmLocation.getCid());
        }

        if (SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            // at least for Nexus 4, even HSDPA networks broadcast psc
            serving.setPsc(gsmLocation.getPsc());
        }

        final String operator = telephonyManager.getNetworkOperator();
        // getNetworkOperator() may return empty string, probably due to dropped connection
        if (operator != null && operator.length() > 3) {
            serving.setOperator(operator);
            serving.setMcc(operator.substring(0, 3));
            serving.setMnc(operator.substring(3));
        } else {
            Log.e(TAG, "Error retrieving network operator, skipping cell");
            return null;
        }

        final String networkOperatorName = telephonyManager.getNetworkOperatorName();
        if (networkOperatorName != null) {
            serving.setOperatorName(networkOperatorName);
        } else {
            Log.e(TAG, "Error retrieving network operator's name, skipping cell");
            return null;
        }

        serving.setArea(gsmLocation.getLac());
        serving.setStrengthdBm(gsmStrengthDbm);
        serving.setStrengthAsu(gsmStrengthAsu);
        return serving;
    }

    /**
     * Returns an array list with neighboring cells
     * Please note:
     * 1) Not all cell phones deliver neighboring cells (e.g. Samsung Galaxy)
     * 2) If in 3G mode, most devices won't deliver cell ids
     *
     * @param serving {@link CellRecord}
     *                Serving cell, used to complete missing api information for neighbor cells (mcc, mnc, ..)
     * @param cellPos {@link PositionRecord}
     *                Current position
     * @return list of neigboring cell records
     */
    private ArrayList<CellRecord> processNeighbors(final CellRecord serving, final PositionRecord cellPos) {
        final ArrayList<CellRecord> neighbors = new ArrayList<>();

        final ArrayList<NeighboringCellInfo> neighboringCellInfos = (ArrayList<NeighboringCellInfo>) telephonyManager.getNeighboringCellInfo();

        if (serving == null) {
            Log.e(TAG, "Can't process neighbor cells: we need a serving cell first");
            return neighbors;
        }

        if (neighboringCellInfos == null) {
            Log.i(TAG, "Neigbor cell list null. Maybe not supported by your phone..");
            return neighbors;
        }

        // TODO: neighbor cell information in 3G mode is unreliable: lots of n/a in data.. Skip neighbor cell logging when in 3G mode or try to autocomplete missing data
        for (final NeighboringCellInfo ci : neighboringCellInfos) {
            final boolean skip = !CellValidator.isValidNeigbor(ci);
            if (!skip) {
                // add neigboring cells
                final CellRecord neighbor = new CellRecord(session);
                // TODO: unelegant: implicit conversion from UTC to YYYYMMDDHHMMSS in begin.setTimestamp
                neighbor.setOpenBmapTimestamp(cellPos.getOpenBmapTimestamp());
                neighbor.setBeginPosition(cellPos);
                // so far we set end position = begin position
                neighbor.setEndPosition(cellPos);
                neighbor.setIsServing(false);
                neighbor.setIsNeighbor(true);

				/*
				 * TelephonyManager doesn't provide all data for NEIGHBOURING cells:
				 * MCC, MNC, Operator and Operator name are missing.
				 * Thus, use data from last SERVING cell.
				 *
				 * In typical cases this won't cause any problems, nevertheless problems can occur
				 * 	- near country border, where NEIGHBOURING cell can have a different MCC, MNC or Operator
				 *   - ... ?
				 */

                neighbor.setMnc(serving.getMnc());
                neighbor.setMcc(serving.getMcc());
                neighbor.setOperator(serving.getOperator());
                neighbor.setOperatorName(serving.getOperatorName());

                final int networkType = ci.getNetworkType();
                neighbor.setNetworkType(networkType);

                if (networkType == TelephonyManager.NETWORK_TYPE_GPRS || networkType == TelephonyManager.NETWORK_TYPE_EDGE) {
                    // GSM cell
                    neighbor.setIsCdma(false);

                    neighbor.setLogicalCellId(ci.getCid());
                    neighbor.setArea(ci.getLac());
                    neighbor.setStrengthdBm(-113 + 2 * ci.getRssi());
                    neighbor.setStrengthAsu(ci.getRssi());

                } else if (networkType == TelephonyManager.NETWORK_TYPE_UMTS || networkType == TelephonyManager.NETWORK_TYPE_HSDPA
                        || networkType == TelephonyManager.NETWORK_TYPE_HSUPA || networkType == TelephonyManager.NETWORK_TYPE_HSPA) {
                    // UMTS cell
                    neighbor.setIsCdma(false);
                    neighbor.setPsc(ci.getPsc());

                    neighbor.setStrengthdBm(ci.getRssi());

                    // There are several approaches on calculating ASU strength in UMTS:
                    // 1) Wikipedia: ASU = dBm + 116
                    // 	  @see http://en.wikipedia.org/wiki/Mobile_phone_signal#ASU
                    // 2) Android way: ASU = (dbm + 113) / 2
                    //	  @see TelephonyManager.getAllCellInfo.getCellStrength.getAsu() TelephonyManager.getAllCellInfo.getCellStrength.getDbm()
                    // 	  @see http://developer.android.com/reference/android/telephony/NeighboringCellInfo.html#getRssi()
                    final int asu = (int) Math.round((ci.getRssi() + 113.0) / 2.0);
                    neighbor.setStrengthAsu(asu);
                } else if (networkType == TelephonyManager.NETWORK_TYPE_CDMA) {
                    // TODO what's the strength in cdma mode? API unclear
                    neighbor.setIsCdma(true);
                }

                // not available for neighboring cells
                //cell.setMcc(mcc);
                //cell.setMnc(mnc);
                //cell.setOperator(operator);
                //cell.setOperatorName(operatorName);

                neighbors.add(neighbor);
            }
        }

        return neighbors;
    }

    /**
     * Broadcasts human-readable description of last cell.
     *
     * @param cell Cell info
     */
    private void broadcastCellInfo(final CellRecord cell) {
        if (cell == null) {
            Log.e(TAG, "Broadcasting error: cell record was null");
            return;
        }

        Log.v(TAG, "Broadcasting cell " + cell.toString());

        String cellId = String.valueOf(cell.getLogicalCellId());
        if (cell.isCdma()) {
            cellId = String.format("%s-%s%s", cell.getSystemId(), cell.getNetworkId(), cell.getBaseId());
        }

        EventBus.getDefault().post(new onCellSaved(
                cell.getOperatorName(),
                cell.getMcc(),
                cell.getMnc(),
                cell.getSystemId(),
                cell.getNetworkId(),
                cell.getBaseId(),
                cell.getArea(),
                cellId,
                CellRecord.TECHNOLOGY_MAP().get(cell.getNetworkType()),
                cell.getStrengthdBm()));
    }


    /**
     * Starts wireless tracking
     *
     * @param sessionId
     */
    private void startTracking(final long sessionId) {
        Log.d(TAG, "Start tracking on session " + sessionId);
        isTracking = true;
        this.session = sessionId;


        dataHelper.saveMetaData(new MetaData(
                Build.MANUFACTURER,
                Build.MODEL,
                Build.VERSION.RELEASE,
                Constants.SWID,
                Constants.SW_VERSION,
                sessionId));
    }

    /**
     * Stops wireless Logging
     */
    private void stopTracking() {
        Log.d(TAG, "Stop tracking on session " + session);
        isTracking = false;
        session = Constants.SESSION_NOT_TRACKING;
    }

    @Subscribe
    public void onEvent(onCellScannerStart event) {
        Log.d(TAG, "ACK onCellScannerStart event");
        session = event.session;
        session = event.session;
        savedAt = new Location("DUMMY");
        startTracking(session);
    }


    @Subscribe
    public void onEvent(onCellScannerStop event) {
        Log.d(TAG, "ACK onCellScannerStop event");
        stopTracking();
    }

    @Subscribe
    public void onEvent(onLocationUpdated event) {
        if (!isTracking) {
            return;
        }

        final Location location = event.location;
        if (location == null) {
            return;
        }

        final String source = location.getProvider();

        // do nothing, if required minimum gps accuracy is not given
        if (!acceptableAccuracy(location)) {
            Log.i(TAG, "GPS accuracy to bad (" + location.getAccuracy() + "m). Ignoring");
            return;
        }

        /*
         * two criteria are required for cells updates
         * 		distance > MIN_CELL_DISTANCE
         * 		elapsed time (in milli seconds) > MIN_CELL_TIME_INTERVAL
         */
        if (acceptableDistance(location, savedAt)) {
            Log.d(TAG, "Cell update. Distance " + location.distanceTo(savedAt));
            final boolean resultOk = updateCells(location, source);

            if (resultOk) {
                //Log.i(TAG, "Successfully saved cell");
                savedAt = location;
            }
        } else {
            Log.i(TAG, "Cell update skipped: either to close to last location or interval < " + MIN_CELL_TIME_INTERVAL / 2000 + " seconds");
        }


        lastLocation = location;
        lastLocationProvider = source;
    }

    /**
     * Checks, whether bssid exists in wifi catalog.
     *
     * @param bssid
     * @return Returns
     */
    private CatalogStatus checkCatalogStatus(final String bssid) {
        if (wifiCatalog == null) {
            Log.w(TAG, "Catalog not available");
            return CatalogStatus.NEW;
        }

        try {
			/*
			 * Caution:
			 * 		Requires wifi catalog's bssid in LOWER CASE. Otherwise no records are returned
			 *
			 *		If wifi catalog's bssid aren't in LOWER case, consider SELECT bssid FROM wifi_zone WHERE LOWER(bssid) = ?
			 *		Drawback: can't use indices then
			 */
            final Cursor exists = wifiCatalog.rawQuery("SELECT bssid, source FROM wifi_zone WHERE bssid = ?", new String[]{bssid.replace(":", "").toUpperCase()});
            if (exists.moveToFirst()) {
                final int source = exists.getInt(1);
                exists.close();
                if (source == CatalogStatus.OPENBMAP.ordinal()) {
                    Log.i(TAG, bssid + " is in openbmap reference database");
                    return CatalogStatus.OPENBMAP;
                } else {
                    Log.i(TAG, bssid + " is in local reference database");
                    return CatalogStatus.LOCAL;
                }
            } else {
                Log.i(TAG, bssid + " is NOT in reference database");
                exists.close();
                //mRefdb.close();
                return CatalogStatus.NEW;
            }
        } catch (final SQLiteException e) {
            Log.e(TAG, "Couldn't open catalog");
            return CatalogStatus.NEW;
        }
    }


    /**
     * Checks whether accuracy is good enough by testing whether accuracy is available and below settings threshold
     *
     * @param location
     * @return true if location has accuracy that is acceptable
     */
    private boolean acceptableAccuracy(final Location location) {
        return location.hasAccuracy() && (location.getAccuracy() <= Float.parseFloat(
                prefs.getString(Preferences.KEY_REQ_GPS_ACCURACY, Preferences.DEFAULT_REQ_GPS_ACCURACY)));
    }


    /**
     * Ensures that there is a minimum distance and minimum time delay between two cell updates.
     * If in DEMO_MODE this behaviour is overridden and cell updates are triggered as fast as possible
     *
     * @param current current position
     * @param last    position of last cell update
     * @return true if distance and time since last cell update are ok or if in demo mode
     */
    private boolean acceptableDistance(final Location current, final Location last) {
        return (current.distanceTo(last) > Float.parseFloat(
                prefs.getString(Preferences.KEY_MIN_CELL_DISTANCE, Preferences.DEFAULT_MIN_CELL_DISTANCE))
                && (current.getTime() - last.getTime() > MIN_CELL_TIME_INTERVAL) || DEMO_MODE);
    }

}