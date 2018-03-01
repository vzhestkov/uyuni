/**
 * Copyright (c) 2018 SUSE LLC
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

package com.suse.manager.webui.controllers;

import com.google.gson.reflect.TypeToken;
import com.redhat.rhn.domain.iss.IssFactory;
import com.redhat.rhn.domain.role.RoleFactory;
import com.redhat.rhn.domain.scc.SCCCachingFactory;
import com.redhat.rhn.domain.user.User;
import com.redhat.rhn.manager.content.ContentSyncManager;
import com.redhat.rhn.manager.content.MgrSyncProductDto;
import com.redhat.rhn.manager.setup.ProductSyncManager;
import com.redhat.rhn.taskomatic.TaskoFactory;
import com.redhat.rhn.taskomatic.domain.TaskoRun;
import com.suse.manager.model.products.Extension;
import com.suse.manager.model.products.Product;
import com.suse.manager.webui.utils.SparkApplicationHelper;
import com.suse.utils.Json;
import org.apache.log4j.Logger;
import spark.ModelAndView;
import spark.Request;
import spark.Response;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Controller class providing backend code for the Products
 */
public class ProductsController {

    private static final String ISS_MASTER = "issMaster";
    private static final String REFRESH_NEEDED = "refreshNeeded";
    private static final String REFRESH_RUNNING = "refreshRunning";

    private static Logger log = Logger.getLogger(ImageBuildController.class);

    private ProductsController() { }

    /**
     * Displays the Products page
     *
     * @param request the request
     * @param response the response
     * @param user the user
     * @return the ModelAndView object to render the page
     */
    public static ModelAndView show(Request request, Response response, User user) {
        TaskoRun latestRun = TaskoFactory.getLatestRun("mgr-sync-refresh-bunch");

        Map<String, Object> data = new HashMap<>();

        data.put(ISS_MASTER, String.valueOf(IssFactory.getCurrentMaster() == null));
        data.put(REFRESH_NEEDED, String.valueOf(SCCCachingFactory.refreshNeeded()));
        data.put(REFRESH_RUNNING, String.valueOf(latestRun != null && latestRun.getEndTime() == null));

        return new ModelAndView(data, "products/show.jade");
    }

    public static String synchronizeProducts(Request request, Response response, User user) {
        if (!user.hasRole(RoleFactory.SAT_ADMIN)) {
            throw new IllegalArgumentException("Must be SAT_ADMIN to synchronize products");
        }
        try {
            ContentSyncManager csm = new ContentSyncManager();
            csm.updateSUSEProducts(csm.getProducts());
        }
        catch (Exception e) {
            log.fatal(e.getMessage(), e);
            return SparkApplicationHelper.json(response, false);
        }
        return SparkApplicationHelper.json(response, true);
    }

    public static String synchronizeChannelFamilies(Request request, Response response, User user) {
        if (!user.hasRole(RoleFactory.SAT_ADMIN)) {
            throw new IllegalArgumentException("Must be SAT_ADMIN to synchronize products");
        }
        try {
            ContentSyncManager csm = new ContentSyncManager();
            csm.updateChannelFamilies(csm.readChannelFamilies());
        }
        catch (Exception e) {
            log.fatal(e.getMessage(), e);
            return SparkApplicationHelper.json(response, false);
        }
        return SparkApplicationHelper.json(response, true);
    }

    public static String synchronizeChannels(Request request, Response response, User user) {
          if (!user.hasRole(RoleFactory.SAT_ADMIN)) {
            throw new IllegalArgumentException("Must be SAT_ADMIN to synchronize products");
          }
          try {
            ContentSyncManager csm = new ContentSyncManager();
            csm.updateChannels(null);
        }
        catch (Exception e) {
            log.fatal(e.getMessage(), e);
            return SparkApplicationHelper.json(response, false);
        }
        return SparkApplicationHelper.json(response, true);
    }

    public static String synchronizeSubscriptions(Request request, Response response, User user) {
        try {
            ContentSyncManager csm = new ContentSyncManager();
            csm.updateSubscriptions(csm.getSubscriptions());
        }
        catch (Exception e) {
            log.fatal(e.getMessage(), e);
            return SparkApplicationHelper.json(response, false);
        }
        return SparkApplicationHelper.json(response, true);
    }

    public static String synchronizeProductChannels(Request request, Response response, User user) {
        if (!user.hasRole(RoleFactory.SAT_ADMIN)) {
            throw new IllegalArgumentException("Must be SAT_ADMIN to synchronize products");
        }
        try {
            ContentSyncManager csm = new ContentSyncManager();
            csm.updateSUSEProductChannels(csm.getAvailableChannels(csm.readChannels()));
        }
        catch (Exception e) {
            log.fatal(e.getMessage(), e);
            return SparkApplicationHelper.json(response, false);
        }
        return SparkApplicationHelper.json(response, true);
    }

    public static String addProduct(Request request, Response response, User user) {
        List<String> identifiers = Json.GSON.fromJson(request.body(), new TypeToken<List<String>>() {
        }.getType());
        if (!user.hasRole(RoleFactory.SAT_ADMIN)) {
            throw new IllegalArgumentException("Must be SAT_ADMIN to synchronize products");
        }
        if (log.isDebugEnabled()) {
            log.debug("Add/Sync products: " + identifiers);
        }
        Map<String, Optional<? extends Throwable>> stringOptionalMap =
                new ProductSyncManager().addProducts(identifiers, user);

        stringOptionalMap.forEach((k, v) -> {
            v.ifPresent(error -> {
                log.fatal("addProduct failed for " + k, error);
            });
        });

        Map<String, Boolean> collect = stringOptionalMap.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                kv -> kv.getValue().isPresent()
        ));
        return SparkApplicationHelper.json(response, collect);
    }

    /**
     * Returns JSON data for the SUSE products
     *
     * @param request the request
     * @param response the response
     * @param user the user
     * @return JSON result of the API call
     */
    public static String data(Request request, Response response, User user) {
        Map<String, Object> data = new HashMap<>();
        try {
            ContentSyncManager csm = new ContentSyncManager();
            Collection<MgrSyncProductDto> products = csm.listProducts(csm.listChannels());
            List<Product> jsonProducts = products.stream().map(
                syncProduct -> new Product(
                    syncProduct.getId(),
                    syncProduct.getIdent(),
                    syncProduct.getFriendlyName(),
                    syncProduct.getArch(),
                    syncProduct.isRecommended(),
                    syncProduct.getExtensions().stream().map(s ->
                       new Extension(
                           s.getId(),
                           s.getIdent(),
                           s.getFriendlyName(),
                           s.getArch(),
                           s.isRecommended()
                       )
                    ).collect(Collectors.toSet())
                )
            ).collect(Collectors.toList());
            data.put("baseProducts", jsonProducts);
        }
        catch (Exception e) {
            log.error("Exception while rendering products: " + e.getMessage());
            data.put("error", "Exception while fetching products: " + e.getMessage());
        }

        return SparkApplicationHelper.json(response, data);
    }
}
