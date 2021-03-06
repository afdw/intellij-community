/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.keychain

import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.splitData
import com.intellij.ide.passwordSafe.PasswordSafe

class FileCredentialsStore() : CredentialsStore {
  override fun get(host: String?, sshKeyFile: String?): Credentials? {
    if (host == null) {
      return null
    }

    val data = PasswordSafe.getInstance().getPassword("ics-" + (sshKeyFile ?: host)) ?: return null
    if (sshKeyFile == null) {
      return splitData(data)
    }
    else {
      return Credentials(sshKeyFile, data)
    }
  }

  override fun reset(host: String) {
    PasswordSafe.getInstance().setPassword("ics-" + host, null)
  }

  override fun save(host: String?, credentials: Credentials, sshKeyFile: String?) {
    val accountName: String = sshKeyFile ?: host!!
    PasswordSafe.getInstance().setPassword("ics-" + accountName, if (sshKeyFile == null) credentials.toString() else credentials.password!!)
  }
}