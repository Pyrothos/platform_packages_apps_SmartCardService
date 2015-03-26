/*
 * Copyright (C) 2011, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Contributed by: Giesecke & Devrient GmbH.
 */

package org.simalliance.openmobileapi.service;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.os.AsyncTask;
import android.os.IBinder;
import android.os.RemoteException;

import java.io.IOException;
import java.io.PrintWriter;
import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;


import android.content.pm.PackageManager;
import android.util.Log;

import org.simalliance.openmobileapi.service.security.AccessControlEnforcer;
import org.simalliance.openmobileapi.service.security.ChannelAccess;


/**
 * Smartcard service base class for terminal resources.
 */
public class Terminal {

    private static final String _TAG = "Terminal";

    protected Context mContext;

    protected final String mName;

    protected int mIndex;

    protected ITerminalService mTerminalService;

    protected ServiceConnection mTerminalConnection;

    private final ArrayList<Session> mSessions
            = new ArrayList<Session>();

    private final Object mLock = new Object();

    /* Async task */
    InitialiseTask mInitialiseTask;

    private BroadcastReceiver mSEReceiver;

    protected boolean mDefaultApplicationSelectedOnBasicChannel = true;

 
    /**
     * For each Terminal there will be one AccessController object.
     */
    private AccessControlEnforcer mAccessControlEnforcer;

    public Terminal(Context context, String name, ResolveInfo info) {
        mContext = context;
        mName = name;
        mTerminalConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
                mTerminalService = ITerminalService.Stub.asInterface(iBinder);
                mInitialiseTask = new InitialiseTask();
                mInitialiseTask.execute();
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                mTerminalService = null;
            }
        };

        mContext.bindService(
                new Intent().setClassName(info.serviceInfo.packageName,
                        info.serviceInfo.name),
                mTerminalConnection,
                Context.BIND_AUTO_CREATE);
    }

    private class InitialiseTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

        }

        @Override
        protected Void doInBackground(Void... arg0) {

            try {
                initializeAccessControl(false);
            } catch (Exception e) {
                // do nothing since this is called where nobody can react.
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            super.onPostExecute(result);
            registerSEStateChangedEvent();
            mInitialiseTask = null;
        }
    }

    public void registerSEStateChangedEvent() {
        Log.v(_TAG, "register to SE state change event");
        try {
            IntentFilter intentFilter = new IntentFilter(
                    mTerminalService.getSEChangeAction());
            mSEReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        if (mTerminalService.getSEChangeAction().equals(intent
                                .getAction())) {
                            initializeAccessControl(
                                    true);
                        }
                    }
                    catch(RemoteException e) {
                        e.printStackTrace();
                    }
                }
            };
            mContext.registerReceiver(mSEReceiver, intentFilter);
        } catch(RemoteException e) {
            e.printStackTrace();
        }
    }

    /**
     * Initalizes Access Control. At least the refresh tag is read and if it
     * differs to the previous one (e.g. is null) the all access rules are read.
     *
     * @param reset
     */
    public synchronized boolean initializeAccessControl(
            boolean reset) {
        boolean result = true;
        Log.i(_TAG, "Initializing Access Control");

        boolean isCardPresent;
        try {
            isCardPresent = isCardPresent();
        } catch (Exception e) {
            isCardPresent = false;

        }

        if (isCardPresent) {
            Log.i(_TAG,
                    "Initializing Access Control for "
                            + getName());
            if (reset) {
                resetAccessControl();
            }
            if (mAccessControlEnforcer == null) {
                mAccessControlEnforcer = new AccessControlEnforcer(this);
            }
            result &= mAccessControlEnforcer.initialize(true, new ISmartcardServiceCallback.Stub(){});
        } else {
            Log.i(_TAG, "NOT initializing Access Control for "
                    + getName() + " SE not present.");
        }

        return result;
    }

    public void onSmartcardServiceShutdown() {
        try {
            closeSessions(new SmartcardError());
        } catch (Exception ignore) {
        }
        // Cancel the inialization background task if still running
        if (mInitialiseTask != null) {
            mInitialiseTask.cancel(true);
        }
        mInitialiseTask = null;
        mContext.unregisterReceiver(mSEReceiver);
        mSEReceiver = null;
        mContext.unbindService(mTerminalConnection);
    }

    /**
     * Closes the defined Session and all its allocated resources. <br>
     * After calling this method the Session can not be used for the
     * communication with the Secure Element any more.
     *
     * @param session the Session that should be closed
     * @throws RemoteException
     * @throws NullPointerException if Session is null
     */
    synchronized void closeSession(Session session, SmartcardError error) {
        if (session == null) {
            throw new NullPointerException("session is null");
        }
        if (!session.isClosed()) {
            session.closeChannels(error);
            session.setClosed();
        }
        mSessions.remove(session);
    }

    private void closeSessions(SmartcardError error) {
        synchronized (mLock) {
            Iterator<Session> iter = mSessions.iterator();
            while (iter.hasNext()) {
                Session session = iter.next();
                closeSession(session, error);
                iter = mSessions.iterator();
            }
            mSessions.clear();
        }
    }

    protected Channel getBasicChannel() {
        for (Session session : mSessions) {
            Channel basicChannel = session.getBasicChannel();
            if (basicChannel != null) {
                return basicChannel;
            }
        }
        return null;
    }

    public String getName() {
        return mName;
    }

    /**
     * Implementation of the MANAGE CHANNEL open and SELECT commands.
     *
     * @param aid The aid of the applet to be selected.
     *
     * @return the number of the logical channel according to ISO 7816-4.
     *
     * @throws Exception If the channel could not be opened.
     */
    public OpenLogicalChannelResponse internalOpenLogicalChannel(byte[] aid)
            throws Exception {
        SmartcardError error = new SmartcardError();
        try {
            OpenLogicalChannelResponse response = mTerminalService.internalOpenLogicalChannel(aid, error);
            Exception ex = error.createException();
            if(ex != null) {
                throw ex;
            }
            return response;
        } catch(RemoteException e) {
            error.throwException();
            throw e;
        }
    }

    /**
     * Implementation of the MANAGE CHANNEL close command.
     *
     * @param channelNumber The channel to be closed.
     *
     */
    public void internalCloseLogicalChannel(int channelNumber) {
        if(channelNumber == 0) {
            byte[] selectCommand = new byte[5];
            selectCommand[0] = 0x00;
            selectCommand[1] = (byte) 0xA4;
            selectCommand[2] = 0x04;
            selectCommand[3] = 0x00;
            selectCommand[4] = 0x00;
            try {
                transmit(
                        selectCommand, 2, 0x9000, 0xFFFF, "SELECT");
            } catch (Exception exp) {
                // Selection of the default application fails
                try {
                    Log.v(SmartcardService._TAG,
                            "Close basic channel - Exception : "
                                    + exp.getLocalizedMessage());
                    if (getAccessControlEnforcer() != null) {
                        byte[] aid = AccessControlEnforcer
                                .getDefaultAccessControlAid();
                        selectCommand = new byte[aid.length + 6];
                        selectCommand[0] = 0x00;
                        selectCommand[1] = (byte) 0xA4;
                        selectCommand[2] = 0x04;
                        selectCommand[3] = 0x00;
                        selectCommand[4] = (byte) aid.length;
                        System.arraycopy(aid, 0, selectCommand, 5, aid.length);
                        // TODO: also accept 62XX and 63XX as valid SW
                        transmit(
                                selectCommand, 2, 0x9000, 0xFFFF, "SELECT");
                    }
                } catch (NoSuchElementException exp2) {
                    // Access Control Applet not available => Don't care
                }
            }
        }

        SmartcardError error = new SmartcardError();
        try {
            mTerminalService.internalCloseLogicalChannel(channelNumber, error);
            error.throwException();
        } catch(RemoteException e) {
            error.throwException();
        }
    }

    /**
     * Implements the terminal specific transmit operation.
     *
     * @param command the command APDU to be transmitted.
     * @return the response APDU received.
     */
    public byte[] internalTransmit(byte[] command) {
        SmartcardError error = new SmartcardError();
        try {
            byte[] response = mTerminalService.internalTransmit(command, error);
            error.throwException();
            return response;
        } catch(RemoteException e) {
            error.throwException();
            return null;
        }
    }

    /**
     * Returns the ATR of the connected card or null if the ATR is not
     * available.
     *
     * @return the ATR of the connected card or null if the ATR is not
     *         available.
     */
    public byte[] getAtr() {
        try{
            return mTerminalService.getAtr();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Returns <code>true</code> if a card is present; <code>false</code>
     * otherwise.
     *
     * @return <code>true</code> if a card is present; <code>false</code>
     *         otherwise.
     * @throws CardException if card presence information is not available.
     */
    boolean isCardPresent() throws Exception {
        return mTerminalService.isCardPresent();
    }

    public Channel openBasicChannel(
            Session session,
            byte[] aid,
            ISmartcardServiceCallback callback)
                    throws Exception {
        if (callback == null) {
            throw new NullPointerException("callback must not be null");
        }

        if (getBasicChannel() != null) {
            throw new IllegalStateException("basic channel in use");
        }
        Channel basicChannel;
        if (aid == null) {
            if (!mDefaultApplicationSelectedOnBasicChannel) {
                throw new IllegalStateException("default application is not selected");
            }
            basicChannel = new Channel(session, this, 0, null, callback);
            basicChannel.hasSelectedAid(false, null);

        } else {
            // Select command
            if (aid == null) {
                throw new NullPointerException("aid must not be null");
            }
            byte[] selectResponse = null;
            byte[] selectCommand = new byte[aid.length + 6];
            selectCommand[0] = 0x00;
            selectCommand[1] = (byte) 0xA4;
            selectCommand[2] = 0x04;
            selectCommand[3] = 0x00;
            selectCommand[4] = (byte) aid.length;
            System.arraycopy(aid, 0, selectCommand, 5, aid.length);
            try {
                // TODO: also accept 62XX and 63XX as valid SW
                selectResponse = transmit(
                        selectCommand, 2, 0x9000, 0xFFFF, "SELECT");
            } catch (Exception exp) {
                throw new NoSuchElementException(exp.getMessage());
            }

            basicChannel = new Channel(session, this, 0, selectResponse, callback);
            basicChannel.hasSelectedAid(true, aid);
            mDefaultApplicationSelectedOnBasicChannel = false;
        }
        return basicChannel;

    }

    public boolean isConnected() {
        return (mTerminalService != null);
    }

    /**
     * Transmits the specified command and returns the response. Optionally
     * checks the response length and the response status word. The status word
     * check is implemented as follows (sw = status word of the response):
     * <p>
     * if ((sw & swMask) != (swExpected & swMask)) throw new CardException();
     * </p>
     *
     * @param cmd the command APDU to be transmitted.
     * @param minRspLength the minimum length of received response to be
     *            checked.
     * @param swExpected the response status word to be checked.
     * @param swMask the mask to be used for response status word comparison.
     * @param commandName the name of the smart card command for logging
     *            purposes. May be <code>null</code>.
     * @return the response received.
     */
    public synchronized byte[] transmit(
            byte[] cmd,
            int minRspLength,
            int swExpected,
            int swMask,
            String commandName) {

        byte[] rsp= internalTransmit(cmd);
        if (rsp.length >= 2) {
            int sw1 = rsp[rsp.length - 2] & 0xFF;
            if (sw1 == 0x6C) {
                cmd[cmd.length - 1] = rsp[rsp.length - 1];
                rsp = internalTransmit(cmd);
            } else if (sw1 == 0x61) {
                byte[] getResponseCmd = new byte[] {
                        cmd[0], (byte) 0xC0, 0x00, 0x00, 0x00
                };
                byte[] response = new byte[rsp.length - 2];
                System.arraycopy(rsp, 0, response, 0, rsp.length - 2);
                while (true) {
                    getResponseCmd[4] = rsp[rsp.length - 1];
                    rsp = internalTransmit(getResponseCmd);
                    if (rsp.length >= 2 && rsp[rsp.length - 2] == 0x61) {
                        response = Util.appendResponse(
                                response, rsp, rsp.length - 2);
                    } else {
                        response = Util.appendResponse(response, rsp, rsp.length);
                        break;
                    }
                }
                rsp = response;
            }
        }
        if (minRspLength > 0) {
            if (rsp == null || rsp.length < minRspLength) {
                throw new IllegalStateException(
                        Util.createMessage(commandName, "response too small"));
            }
        }
        if (swMask != 0) {
            if (rsp == null || rsp.length < 2) {
                throw new IllegalArgumentException(
                        Util.createMessage(commandName, "SW1/2 not available"));
            }
            int sw1 = rsp[rsp.length - 2] & 0xFF;
            int sw2 = rsp[rsp.length - 1] & 0xFF;
            int sw = (sw1 << 8) | sw2;
            if ((sw & swMask) != (swExpected & swMask)) {
                throw new IllegalArgumentException(Util.createMessage(commandName, sw));
            }
        }
        return rsp;
    }

    public byte[] simIOExchange(int fileID, String filePath, byte[] cmd)
            throws Exception {
        SmartcardError error = new SmartcardError();
        try {
            return mTerminalService.simIOExchange(fileID, filePath, cmd, error);
        } catch (RemoteException e) {
            throw new Exception("SIM IO error!");
        }
    }

    public ChannelAccess setUpChannelAccess(
            PackageManager packageManager,
            byte[] aid,
            String packageName,
            ISmartcardServiceCallback callback) {
        if (mAccessControlEnforcer == null) {
            throw new AccessControlException(
                    "Access Control Enforcer not properly set up");
        }
        mAccessControlEnforcer.setPackageManager(packageManager);
        return mAccessControlEnforcer.setUpChannelAccess(
                aid, packageName, callback);
    }

    public AccessControlEnforcer getAccessControlEnforcer() {
        return mAccessControlEnforcer;
    }

    public synchronized void resetAccessControl() {
        if (mAccessControlEnforcer != null) {
            mAccessControlEnforcer.reset();
        }
    }


    /**
     * Implementation of the SmartcardService Reader interface according to
     * OMAPI.
     */
    final class SmartcardServiceReader extends ISmartcardServiceReader.Stub {
        @Override
        public String getName(SmartcardError error) throws RemoteException {
            Util.clearError(error);
            return Terminal.this.getName();
        }

        @Override
        public boolean isSecureElementPresent(SmartcardError error)
                throws RemoteException {
            Util.clearError(error);
            try {
                return Terminal.this.isCardPresent();
            } catch (Exception e) {
                Util.setError(error, e);
            }
            return false;
        }

        @Override
        public ISmartcardServiceSession openSession(SmartcardError error)
                throws RemoteException {
            Util.clearError(error);
            try {
                if (!Terminal.this.isCardPresent()) {
                    Util.setError(
                            error,
                            new IOException("Secure Element is not presented.")
                            );
                    return null;
                }
            } catch (Exception e) {
                Util.setError(error, e);
                return null;
            }

            synchronized (mLock) {
                try {
                    initializeAccessControl(
                            false);
                } catch (Exception e) {
                    Util.setError(error, e);
                    // Reader.openSession() will throw an IOException when
                    // session is null
                    return null;
                }
                Session session = new Session(Terminal.this, mContext);
                mSessions.add(session);

                return session.new SmartcardServiceSession();
            }
        }

        @Override
        public void closeSessions(SmartcardError error) throws RemoteException {

            Util.clearError(error);
            Terminal.this.closeSessions(error);
            if(error.createException() != null) {
                error.throwException();
            }
        }
    }

    public void dump(PrintWriter writer, String prefix) {
        writer.println(prefix + "SMARTCARD SERVICE TERMINAL: " + getName());
        writer.println();

        prefix += "  ";

        writer.println(prefix + "mIsConnected:" + (mTerminalService != null));
        writer.println();

        /* Dump the list of currunlty openned channels */
        writer.println(prefix + "List of open channels:");

        for (Session session : mSessions) {
            if (session != null && !session.isClosed()) {
                session.dump(writer, prefix);
            }
        }

        writer.println();

        /* Dump ACE data */
        if (mAccessControlEnforcer != null) {
            mAccessControlEnforcer.dump(writer, prefix);
        }
    }
}
