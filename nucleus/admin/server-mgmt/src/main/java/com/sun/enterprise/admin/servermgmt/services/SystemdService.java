/*
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package com.sun.enterprise.admin.servermgmt.services;

import com.sun.enterprise.universal.io.SmartFile;
import com.sun.enterprise.universal.process.ProcessManager;
import com.sun.enterprise.universal.process.ProcessManagerException;
import com.sun.enterprise.util.OS;
import com.sun.enterprise.util.ObjectAnalyzer;
import com.sun.enterprise.util.StringUtils;
import com.sun.enterprise.util.io.FileUtils;
import com.sun.enterprise.util.io.ServerDirs;
import java.io.File;
import java.lang.String;
import java.util.*;
import java.util.ArrayList;
import java.util.LinkedList;
import static com.sun.enterprise.admin.servermgmt.services.Constants.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 */
public class SystemdService extends NonSMFServiceAdapter {
    static boolean apropos() {
        if (OS.isLinux()) {
            File systemdDir = new File("/usr/lib/systemd/");
            return systemdDir.exists();
        }
        return false;
    }

    SystemdService(ServerDirs dirs, AppserverServiceType type) {
        super(dirs, type);
        if (!apropos()) {
            // programmer error
            throw new IllegalArgumentException(Strings.get("internal.error",
                    "Constructor called but Systemd Services are not available."));
        }
    }

    @Override
    public void initializeInternal() {
        try {
            getTokenMap().put(SERVICEUSER_START_TN, getServiceUserStart());
            getTokenMap().put(SERVICEUSER_STOP_TN, getServiceUserStop());
            setTemplateFile(TEMPLATE_FILE_NAME);
            checkFileSystem();
            setTarget();
        }
        catch (RuntimeException e) {
            throw e;
        }
        catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public final void createServiceInternal() {
        try {
            handlePreExisting(info.force);

            if (uninstall() && !info.dryRun)
                System.out.println(Strings.get("linux.services.uninstall.good"));
            else
                trace("No preexisting Service with that name was found");

            ServicesUtils.tokenReplaceTemplateAtDestination(getTokenMap(), getTemplateFile().getPath(), target.getPath());
            trace("Target file written: " + target);
            trace("**********   Object Dump  **********\n" + this.toString());

            install();
        }
        catch (RuntimeException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public final void deleteServiceInternal() {
        try {
            uninstall();
        }
        catch (RuntimeException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public final String getSuccessMessage() {
        if (info.dryRun)
            return Strings.get("dryrun");

        return Strings.get("SystemdServiceCreated",
                info.serviceName,
                info.type.toString(),
                target,
                getFinalUser(),
                target.getName());
    }

    // called by outside caller (createService)
    @Override
    public final void writeReadmeFile(String msg) {
        File f = new File(getServerDirs().getServerDir(), README);

        ServicesUtils.appendTextToFile(f, msg);
    }

    @Override
    public final String toString() {
        return ObjectAnalyzer.toStringWithSuper(this);
    }

    @Override
    public final String getLocationArgsStart() {
        if (isDomain()) {
            return " --domaindir " + getServerDirs().getServerParentDir().getPath() + " ";
        }
        else {
            return " --nodedir " + getServerDirs().getServerGrandParentDir().getPath()
                    + " --node " + getServerDirs().getServerParentDir().getName() + " ";
        }
    }

    @Override
    public final String getLocationArgsStop() {
        // exactly the same on Linux
        return getLocationArgsStart();
    }
    ///////////////////////////////////////////////////////////////////////
    //////////////////////////   ALL PRIVATE BELOW    /////////////////////
    ///////////////////////////////////////////////////////////////////////

    private File validate(File dir) {
        if (dir == null)
            return null;

        dir = FileUtils.safeGetCanonicalFile(dir);

        if (!dir.isDirectory())
            return null;

        return dir;
    }

    private void checkFileSystem() {
        File systemd = new File(SYSTEMD);
        systemdDir = validate(systemd);
        checkDir(systemdDir, "no_systemd");
    }

    /**
     * Make sure that the dir exists and that we can write into it
     */
    private void checkDir(File dir, String notDirMsg) {
        if (!dir.isDirectory())
            throw new RuntimeException(Strings.get(notDirMsg, dir));

        if (!dir.canWrite())
            throw new RuntimeException(Strings.get("no_write_dir", dir));
    }

    private void handlePreExisting(boolean force) {
        if (isPreExisting()) {
            if (force) {
                boolean result = target.delete();
                if (!result || isPreExisting()) {
                    throw new RuntimeException(Strings.get("services.alreadyCreated", target, "rm"));
                }
            }
        }
    }

    private boolean isPreExisting() {
        return target.isFile();
    }

    private void install() throws ProcessManagerException {
        enableService();
    }

    private boolean uninstall() {
        disableService();
        if (target.delete())
            trace("Deleted " + target);

        return !target.exists();
    }

    private void enableService() {
        String[] cmds = new String[3];
        cmds[0] = "systemctl";
        cmds[1] = "enable";
        cmds[2] = target.getAbsolutePath();

        String cmd = toString(cmds);
        if (info.dryRun)
            dryRun(cmd);
        else
            callSystemctl(cmds);
    }

    private void disableService() {
        String[] cmds = new String[3];
        cmds[0] = "systemctl";
        cmds[1] = "disable";
        cmds[2] = target.getAbsolutePath();

        String cmd = toString(cmds);
        if (info.dryRun)
            dryRun(cmd);
        else
            callSystemctl(cmds);
    }

    private void callSystemctl(String[] cmds) {
        try {
            ProcessManager mgr = new ProcessManager(cmds);
            mgr.setEcho(false);
            mgr.execute();
            trace("systemctl Output: " + mgr.getStdout() + mgr.getStderr());
        }
        catch (ProcessManagerException e) {
            throw new RuntimeException(Strings.get("systemctl_error", toString(cmds), e));
        }
    }

    private void setTarget() {
        targetName = "GlassFish_" + info.serverDirs.getServerName() + ".service";
        target = new File(SYSTEMD + "/" + targetName);
    }

    private String getServiceUserStart() {
        // if the user is root (e.g. called with sudo and no serviceuser arg given)
        // then do NOT specify a user.
        // on the other hand -- if they specified one or they are logged in as a'privileged'
        // user then use that account.
        String u = getFinalUserButNotRoot();
        hasStartStopTokens = (u != null);

        if (hasStartStopTokens)
            return "User=" + u;
        return "";
    }

    private String getServiceUserStop() {
        return "";
    }

    private String getFinalUser() {
        if (StringUtils.ok(info.serviceUser))
            return info.serviceUser;
        else
            return info.osUser;
    }

    private String getFinalUserButNotRoot() {
        String u = getFinalUser();

        if ("root".equals(u))
            return null;

        return u;
    }

    private String toString(String[] arr) {
        // for creating messages/error reports
        StringBuilder sb = new StringBuilder();

        for (String s : arr)
            sb.append(s).append(" ");

        return sb.toString();
    }
    private String targetName;
    File target;
    File systemdDir;
    private static final String TEMPLATE_FILE_NAME = "linux-systemd.service.template";
    private static final String SYSTEMD = "/etc/systemd/system";
    private boolean hasStartStopTokens = false;
}
