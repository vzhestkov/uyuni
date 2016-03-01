/**
 * Copyright (c) 2016 SUSE LLC
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
package com.suse.manager.webui.utils.gson;

import java.util.Set;

/**
 * JSON representation of a server and a list of custom Salt states.
 */
public class JSONServerCustomStates {

    /** Server id */
    private long sid;

    private Set<JSONCustomState> saltStates;

    /**
     * @return the sever id
     */
    public long getServerId() {
        return sid;
    }

    /**
     * @return the custom Salt states
     */
    public Set<JSONCustomState> getSaltStates() {
        return saltStates;
    }
}
