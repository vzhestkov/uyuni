/**
 * Copyright (c) 2015 SUSE LLC
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */

package com.suse.manager.gatherer;

import com.redhat.rhn.common.conf.Config;
import com.redhat.rhn.common.conf.ConfigDefaults;
import com.redhat.rhn.common.util.http.HttpClientAdapter;
import com.redhat.rhn.domain.server.virtualhostmanager.VirtualHostManager;
import com.redhat.rhn.manager.satellite.Executor;
import com.redhat.rhn.manager.satellite.SystemCommandExecutor;

import com.suse.manager.model.gatherer.GathererModule;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;


/**
 * Runs the virtual-host-gatherer command.
 */
public class GathererRunner {

    private static final String GATHERER_CMD = "/usr/bin/virtual-host-gatherer";
    private static final String LOG_DESTINATION = "/var/log/rhn/gatherer.log";
    /**
     * Logger for this class
     */
    private static Logger logger = Logger.getLogger(GathererRunner.class);

    /**
     * Call gatherer --list-modules and return the result
     * @return the available modules with details
     */
    public Map<String, GathererModule> listModules() {
        Executor e = new SystemCommandExecutor();
        List<String> args = new LinkedList<>();
        args.add(GATHERER_CMD);
        args.add("--list-modules");
        args.add("--logfile");
        args.add(LOG_DESTINATION);

        int exitcode = e.execute(args.toArray(new String[0]));
        if (exitcode != 0) {
            logger.error(e.getLastCommandErrorMessage());
            return null;
        }
        return new GathererJsonIO().readGathererModules(e.getLastCommandOutput());
    }

    /**
     * Runs virtual-host-gatherer against a set of Virtual Host Managers.
     *
     * @param vhms the virtual host managers
     * @return a map from virtual host manager names to
     * (virtual name, {@link JSONHost}) pairs
     */
    public Map<String, Map<String, JSONHost>> run(List<VirtualHostManager> vhms) {
        List<String> args = new LinkedList<>();
        args.add(GATHERER_CMD);
        args.add("--infile");
        args.add("-");
        args.add("--logfile");
        args.add(LOG_DESTINATION);

        Map<String, String> env = new HashMap<String, String>(System.getenv());
        String proxyHostname = ConfigDefaults.get().getProxyHost();
        if (!StringUtils.isBlank(proxyHostname)) {
            String proxyUri = "http://";
            String proxyUsername = ConfigDefaults.get().getProxyUsername();
            String proxyPassword = ConfigDefaults.get().getProxyPassword();
            if (!StringUtils.isBlank(proxyUsername) &&
                    !StringUtils.isBlank(proxyPassword)) {
                proxyUri += proxyUsername + ":" + proxyPassword + "@";
            }
            int proxyPort = ConfigDefaults.get().getProxyPort();
            proxyUri += proxyHostname + ":" + proxyPort + "/";
            env.put("http_proxy", proxyUri);
            env.put("https_proxy", proxyUri);
            logger.debug("Set http(s)_proxy to " + proxyUri);
        }
        String noProxy = Config.get().getString(HttpClientAdapter.NO_PROXY);
        if (noProxy != null) {
            env.put("no_proxy", noProxy);
            logger.debug("Set no_proxy to " + noProxy);
        }

        String[] envp = new String[env.size()];
        int i = 0;
        for (Map.Entry<String, String> e : env.entrySet()) {
            envp[i++] = e.getKey() + "=" + e.getValue();
        }

        Map<String, Map<String, JSONHost>> hosts = null;
        Runtime r = Runtime.getRuntime();
        try {
            Process p = r.exec(args.toArray(new String[0]), envp);
            PrintWriter stdin = new PrintWriter(p.getOutputStream());
            stdin.println(new GathererJsonIO().toJson(vhms));
            stdin.flush();
            stdin.close();

            InputStreamReader irr = new InputStreamReader(p.getInputStream());
            hosts = new GathererJsonIO().readHosts(irr);

            int exitcode = p.waitFor();
            if (exitcode != 0) {
                logger.error("Error while calling the gatherer");
                return null;
            }
        }
        catch (IOException ioe) {
            logger.error("execute(String[])", ioe);
        }
        catch (InterruptedException e) {
            logger.error("execute(String[])", e);
        }
        return hosts;
    }
}
