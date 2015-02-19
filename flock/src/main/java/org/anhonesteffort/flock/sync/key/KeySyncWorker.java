/*
 * *
 *  Copyright (C) 2014 Open Whisper Systems
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.
 * /
 */

package org.anhonesteffort.flock.sync.key;

import android.content.Context;
import android.content.SyncResult;
import android.util.Log;

import org.anhonesteffort.flock.util.guava.Optional;
import org.anhonesteffort.flock.DavAccountHelper;
import org.anhonesteffort.flock.auth.DavAccount;
import org.anhonesteffort.flock.crypto.InvalidMacException;
import org.anhonesteffort.flock.crypto.KeyHelper;
import org.anhonesteffort.flock.crypto.KeyStore;
import org.anhonesteffort.flock.sync.SyncWorker;
import org.anhonesteffort.flock.sync.SyncWorkerUtil;
import org.anhonesteffort.flock.webdav.PropertyParseException;
import org.apache.jackrabbit.webdav.DavException;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Programmer: rhodey
 */
public class KeySyncWorker implements SyncWorker {

  private static final String TAG = "org.anhonesteffort.flock.sync.key.KeySyncWorker";

  private final Context    context;
  private final DavAccount account;
  private final SyncResult result;

  public KeySyncWorker(Context context, DavAccount account, SyncResult result) {
    this.context = context;
    this.account = account;
    this.result  = result;

    Thread.currentThread().setContextClassLoader(context.getClassLoader());
  }

  private void handleMigrationComplete(SyncResult       result,
                                       String           localKeyMaterialSalt,
                                       String           localEncryptedKeyMaterial,
                                       DavKeyCollection keyCollection)
  {
    Log.w(TAG, "handleMigrationComplete()");

    try {

      if (!KeyHelper.masterPassphraseIsValid(context) &&
          !DavAccountHelper.isUsingOurServers(context))
      {
        KeySyncService.showCipherPassphraseInvalidNotification(context);
        return;
      }

    } catch (GeneralSecurityException e) {
      SyncWorkerUtil.handleException(context, e, result);
    } catch (IOException e) {
      SyncWorkerUtil.handleException(context, e, result);
    }

    try {

      Optional<String> remoteKeyMaterialSalt      = keyCollection.getKeyMaterialSalt();
      Optional<String> remoteEncryptedKeyMaterial = keyCollection.getEncryptedKeyMaterial();

      if (!remoteKeyMaterialSalt.isPresent())
        keyCollection.setKeyMaterialSalt(localKeyMaterialSalt);

      if (!remoteEncryptedKeyMaterial.isPresent())
        keyCollection.setEncryptedKeyMaterial(localEncryptedKeyMaterial);

      else if (remoteKeyMaterialSalt.isPresent() &&
               !remoteEncryptedKeyMaterial.get().equals(localEncryptedKeyMaterial))
      {
        try {

          KeyHelper.importSaltAndEncryptedKeyMaterial(context, new String[]{
              remoteKeyMaterialSalt.get(),
              remoteEncryptedKeyMaterial.get()
          });

        } catch (InvalidMacException e) {
          Log.w(TAG, "caught invalid mac exception while importing remote key material, " +
                     "assuming password change for non-flock sync user.");
          KeyStore.saveEncryptedKeyMaterial(context, remoteEncryptedKeyMaterial.get());
          KeySyncService.showCipherPassphraseInvalidNotification(context);
        }
      }

    } catch (PropertyParseException e) {
      SyncWorkerUtil.handleException(context, e, result);
    } catch (DavException e) {
      SyncWorkerUtil.handleException(context, e, result);
    } catch (IOException e) {
      SyncWorkerUtil.handleException(context, e, result);
    } catch (GeneralSecurityException e) {
      SyncWorkerUtil.handleException(context, e, result);
    }
  }

  @Override
  public void run() {
    Log.d(TAG, "now syncing");
    try {

      DavKeyStore davKeyStore = DavAccountHelper.getDavKeyStore(context, account);

      try {

        Optional<String> localKeyMaterialSalt      = KeyHelper.buildEncodedSalt(context);
        Optional<String> localEncryptedKeyMaterial = KeyStore.getEncryptedKeyMaterial(context);

        if (!localKeyMaterialSalt.isPresent() || !localEncryptedKeyMaterial.isPresent()) {
          Log.e(TAG, "missing local key material salt or local encrypted key material.");
          return;
        }

        Optional<DavKeyCollection> keyCollection = davKeyStore.getCollection();

        if (!keyCollection.isPresent()) {
          Log.w(TAG, "key collection is missing");
          return;
        }

        handleMigrationComplete(result, localKeyMaterialSalt.get(), localEncryptedKeyMaterial.get(), keyCollection.get());

      } catch (PropertyParseException e) {
        SyncWorkerUtil.handleException(context, e, result);
      } catch (DavException e) {
        SyncWorkerUtil.handleException(context, e, result);
      } catch (IOException e) {
        SyncWorkerUtil.handleException(context, e, result);
      } finally {
        davKeyStore.closeHttpConnection();
      }

    } catch (IOException e) {
      SyncWorkerUtil.handleException(context, e, result);
    }
  }

  @Override
  public void cleanup() {

  }

}
