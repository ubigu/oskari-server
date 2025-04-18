package fi.mml.map.mapwindow.util;

import java.util.*;
import java.util.stream.Collectors;

import fi.nls.oskari.util.ConversionHelper;
import org.json.JSONArray;
import org.json.JSONObject;

import fi.nls.oskari.domain.User;
import fi.nls.oskari.domain.map.OskariLayer;
import fi.nls.oskari.log.LogFactory;
import fi.nls.oskari.log.Logger;
import fi.nls.oskari.map.layer.OskariLayerService;
import fi.nls.oskari.map.layer.OskariLayerServiceMybatisImpl;
import fi.nls.oskari.map.layer.formatters.LayerJSONFormatter;
import fi.nls.oskari.util.JSONHelper;
import org.oskari.permissions.PermissionService;
import org.oskari.permissions.PermissionServiceMybatisImpl;
import org.oskari.permissions.model.PermissionSet;
import org.oskari.permissions.model.PermissionType;
import org.oskari.permissions.model.Resource;
import org.oskari.permissions.model.ResourceType;

/**
 * Worker class for rendering json objects from domain objects
 */
public class OskariLayerWorker {

    public static final String KEY_LAYERS = "layers";
    public static final boolean PUBLICATION_PERMISSION_OK = true;
    public static final boolean DOWNLOAD_PERMISSION_OK = true;

    private static final Logger LOG = LogFactory.getLogger(OskariLayerWorker.class);

    private static OskariLayerService mapLayerService = new OskariLayerServiceMybatisImpl();
    private static PermissionService permissionService = new PermissionServiceMybatisImpl();

    private final static LayerJSONFormatter FORMATTER = new LayerJSONFormatter();

    /**
     * Gets all the selected map layers
     *
     * @param layerIdList
     * @param user
     * @param lang
     * @param crs
     * @return JSONObject containing the selected layers
     */
    public static JSONObject getListOfMapLayersByIdList(final List<Integer> layerIdList, final User user,
            final String lang, final String crs) {
        final List<OskariLayer> layers = mapLayerService.findByIdList(layerIdList);
        return getListOfMapLayers(layers, user, lang, crs, false, false);
    }
    /**
     * Gets all the selected map layers
     *
     * @param layerIdList List of selected layer IDs
     * @param user        User
     * @param lang        Language
     * @param isPublished Determines the permission type used for the layers (view/published view)
     * @param isSecure    true to modify urls for easier proxy forwarding/false to keep as is
     * @return JSONObject containing the selected layers
     */
    public static JSONObject getListOfMapLayersById(final List<Integer> layerIdList, final User user,
            final String lang, final boolean isPublished, final boolean isSecure, final String crs) {
        final List<OskariLayer> layers = mapLayerService.findByIdList(layerIdList);
        return getListOfMapLayers(layers, user, lang, crs, isPublished, isSecure);
    }

    /**
     * Gets all the map layers the user is permitted to view
     *
     * @param layers      list of layers to render
     * @param user        User
     * @param lang        Language
     * @param isPublished Determines the permission type used for the layers (view/published view)
     * @param isSecure    true to modify urls for easier proxy forwarding/false to keep as is
     * @return JSONObject of map layers
     */
    public static JSONObject getListOfMapLayers(final List<OskariLayer> layers, final User user,
            final String lang, final String crs, final boolean isPublished, final boolean isSecure) {
        List<Resource> resources;
        if (layers.size() < 20) {
            // usually the case with loading the default app setup
            resources = layers.stream()
                    .map(OskariLayer::getId)
                    .map(id -> Integer.toString(id))
                    .map(id -> permissionService.findResource(ResourceType.maplayer, id))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toList());
        } else {
            // more than 20 layers, just get all the permissions
            resources = permissionService.findResourcesByUser(user, ResourceType.maplayer);
        }
        return getListOfMapLayers(layers, user, lang, isSecure, crs, isPublished, new PermissionSet(resources));
    }

    public static List<OskariLayer> getLayersForUser(User user, boolean isPublished) {
        long start = System.currentTimeMillis();
        List<OskariLayer> layers = mapLayerService.findAll();
        LOG.info("Layers read in", System.currentTimeMillis() - start, "ms");
        start = System.currentTimeMillis();
        List<Resource> resources = permissionService.findResourcesByUser(user, ResourceType.maplayer);
        LOG.info("Permissions read in", System.currentTimeMillis() - start, "ms");
        return filterLayersWithResources(layers, new PermissionSet(resources), user, isPublished);
    }

    public static List<OskariLayer> filterLayersWithResources(List<OskariLayer> layers, PermissionSet permissionSet, User user, boolean isPublished) {
        PermissionType forViewing = isPublished ? PermissionType.VIEW_PUBLISHED : PermissionType.VIEW_LAYER;
        return layers.stream()
                .filter(layer -> !layer.isInternal())
                .filter(layer -> layer.isSublayer() ||
                        permissionSet.get(ResourceType.maplayer, getPermissionKey(layer))
                                .map(r -> r.hasPermission(user, forViewing))
                                .orElse(false))
                .collect(Collectors.toList());
    }

    public static JSONObject getListOfMapLayers(final List<OskariLayer> layers,
            final User user,
            final String lang,
            final boolean isSecure,
            final String crs,
            final boolean isPublished,
            final PermissionSet permissionSet) {
        final List<OskariLayer> filtered = filterLayersWithResources(layers, permissionSet, user, isPublished);
        final JSONArray layersList = new JSONArray();
        long start = System.currentTimeMillis();
        for (OskariLayer layer : filtered) {
            try {
                final JSONObject layerJson = FORMATTER.getJSON(layer, lang, isSecure, crs);
                if (layerJson == null) {
                    continue;
                }
                // FIXME: formats isn't used by the frontend, but some capabilities parsing uses the layer JSON stuff
                //  so forcing removal for it here until we can refactor it's usage from internal server code
                layerJson.remove("formats");
                final String permissionKey = getPermissionKey(layer);
                JSONObject permissions = getPermissions(user, permissionKey, permissionSet);
                JSONHelper.putValue(layerJson, "permissions", permissions);

                layersList.put(layerJson);
            } catch(Exception ex) {
                LOG.error(ex);
            }
        }
        LOG.debug("Created JSON in", System.currentTimeMillis() - start, "ms");
        LOG.info("Returning", layersList.length(), "/", layers.size(), "layers");

        final JSONObject result = new JSONObject();
        JSONHelper.putValue(result, KEY_LAYERS, layersList);
        return result;
    }

    public static String getPermissionKey(OskariLayer layer) {
        return Integer.toString(layer.getId());
    }

    /**
     * Convenience method to get JSON for single layer.
     * TODO: maybe tune the implementation a bit
     * @param layer
     * @param user
     * @param lang
     * @return
     */
    public static JSONObject getMapLayerJSON(final OskariLayer layer, final User user,
            final String lang, final String crs) {

        final List<OskariLayer> list = new ArrayList<>(1);
        list.add(layer);
        final JSONObject obj = OskariLayerWorker.getListOfMapLayers(list, user, lang, crs, false, false);
        JSONArray layers = JSONHelper.getJSONArray(obj, KEY_LAYERS);
        try {
            return layers.getJSONObject(0);
        } catch (Exception e) {
            LOG.warn("Error creating layer JSON:", obj);
        }
        return null;
    }

    /**
     * Create permission information for JSON
     *
     * @param user                 Current user
     * @param layerPermissionKey   Layer permission key
     * @param permissionSet        Wrapper containing resources that can be used to check user permissions
     */
    public static JSONObject getPermissions(final User user, final String layerPermissionKey, final PermissionSet permissionSet) {
        final JSONObject permission = new JSONObject();
        Optional<Resource> res = permissionSet.get(ResourceType.maplayer, layerPermissionKey);
        if (!res.isPresent()) {
            return permission;
        }
        Resource resource = res.get();
        if (resource.hasPermission(user, PermissionType.PUBLISH)) {
            JSONHelper.putValue(permission, PermissionType.PUBLISH.getJsonKey(), PUBLICATION_PERMISSION_OK);
        }

        if (resource.hasPermission(user, PermissionType.DOWNLOAD)) {
            JSONHelper.putValue(permission, PermissionType.DOWNLOAD.getJsonKey(), DOWNLOAD_PERMISSION_OK);
        }
        Set<String> PERMISSIONS_TO_SKIP = ConversionHelper.asSet(
                PermissionType.PUBLISH,
                PermissionType.DOWNLOAD,
                PermissionType.VIEW_LAYER,
                PermissionType.VIEW_PUBLISHED).stream().map(PermissionType::name).collect(Collectors.toSet());
        Set<String> otherAvailablePermissionTypes = resource.getPermissionTypes()
                .stream().filter(type -> !PERMISSIONS_TO_SKIP.contains(type)).collect(Collectors.toSet());

        otherAvailablePermissionTypes.stream()
                .filter(permType -> resource.hasPermission(user, permType))
                .forEach(permType -> {
                    try {
                        permType = PermissionType.valueOf(permType).getJsonKey();
                    } catch (IllegalArgumentException notFoundIgnored) {
                        // thrown if this wasn't a value in the enum but an extension permission
                    }
                    JSONHelper.putValue(permission, permType, true);
                });
        if (user.isAdmin()) {
            // admins can always edit layers
            JSONHelper.putValue(permission, PermissionType.EDIT_LAYER.getJsonKey(), true);
        }
        return permission;
    }
}
