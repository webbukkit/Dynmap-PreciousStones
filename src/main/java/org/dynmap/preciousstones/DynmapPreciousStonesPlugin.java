package org.dynmap.preciousstones;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.sacredlabyrinth.Phaed.PreciousStones.FieldFlag;
import net.sacredlabyrinth.Phaed.PreciousStones.PreciousStones;
import net.sacredlabyrinth.Phaed.PreciousStones.managers.ForceFieldManager;
import net.sacredlabyrinth.Phaed.PreciousStones.vectors.Field;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;

public class DynmapPreciousStonesPlugin extends JavaPlugin {
    private static final Logger log = Logger.getLogger("Minecraft");
    private static final String LOG_PREFIX = "[Dynmap-PreciousStones] ";
    private static final String DEF_INFOWINDOW = "<div class=\"infowindow\"><span style=\"font-size:120%;\">%regionname%</span><br /> Owners <span style=\"font-weight:bold;\">%playerowners%</span><br/>Members <span style=\"font-weight:bold;\">%playermembers%</span><br/>Flags<br /><span style=\"font-weight:bold;\">%flags%</span></div>";
    Plugin dynmap;
    DynmapAPI api;
    MarkerAPI markerapi;
    
    PreciousStones ps;
    
    FileConfiguration cfg;
    MarkerSet set;
    long updperiod;
    boolean use3d;
    String infowindow;
    AreaStyle defstyle;
    Map<String, AreaStyle> cusstyle;
    Map<String, AreaStyle> cuswildstyle;
    Map<String, AreaStyle> ownerstyle;
    Set<String> visible;
    Set<String> hidden;
    boolean stop; 
    int maxdepth;
    
    private static class AreaStyle {
        String strokecolor;
        double strokeopacity;
        int strokeweight;
        String fillcolor;
        double fillopacity;
        String label;

        AreaStyle(FileConfiguration cfg, String path, AreaStyle def) {
            strokecolor = cfg.getString(path+".strokeColor", def.strokecolor);
            strokeopacity = cfg.getDouble(path+".strokeOpacity", def.strokeopacity);
            strokeweight = cfg.getInt(path+".strokeWeight", def.strokeweight);
            fillcolor = cfg.getString(path+".fillColor", def.fillcolor);
            fillopacity = cfg.getDouble(path+".fillOpacity", def.fillopacity);
            label = cfg.getString(path+".label", null);
        }

        AreaStyle(FileConfiguration cfg, String path) {
            strokecolor = cfg.getString(path+".strokeColor", "#FF0000");
            strokeopacity = cfg.getDouble(path+".strokeOpacity", 0.8);
            strokeweight = cfg.getInt(path+".strokeWeight", 3);
            fillcolor = cfg.getString(path+".fillColor", "#FF0000");
            fillopacity = cfg.getDouble(path+".fillOpacity", 0.35);
        }
    }
    
    public static void info(String msg) {
        log.log(Level.INFO, LOG_PREFIX + msg);
    }
    public static void severe(String msg) {
        log.log(Level.SEVERE, LOG_PREFIX + msg);
    }

    private class PreciousStonesUpdate implements Runnable {
        public void run() {
            if(!stop)
                updateForceFields();
        }
    }
    
    private Map<String, AreaMarker> resareas = new HashMap<String, AreaMarker>();

    private String formatInfoWindow(Field field, AreaMarker m) {
        String v = "<div class=\"regioninfo\">"+infowindow+"</div>";
        v = v.replace("%regionname%", m.getLabel());
        v = v.replace("%playerowners%", field.getOwner());
        List<String> allowed = field.getAllAllowed();
        String rslt = "";
        for(String s : allowed) {
            if(rslt.length() > 0)
                rslt = rslt + ", ";
            rslt += s;
        }
        v = v.replace("%playermembers%", rslt);
        String flgs = "";
        for(FieldFlag ff : FieldFlag.values()) {
            if(ff == FieldFlag.ALL) continue;
            if(field.hasFlag(ff)) {
                flgs += ff.toString() + "<br/>";
                v = v.replace("%" + ff.toString() + "%", "true");
            }
            else {
                v = v.replace("%" + ff.toString() + "%", "false");
            }
        }
        v = v.replace("%flags%", flgs);
        return v;
    }
    
    private boolean isVisible(String typeid, String owner, String worldname) {
        if((visible != null) && (visible.size() > 0)) {
            if((visible.contains(typeid) == false) && (visible.contains("world:" + worldname) == false) &&
                    (visible.contains(worldname + "/" + typeid) == false) && (visible.contains("owner:" + owner) == false)) {
                return false;
            }
        }
        if((hidden != null) && (hidden.size() > 0)) {
            if(hidden.contains(typeid) || hidden.contains("world:" + worldname) || hidden.contains(worldname + "/" + typeid) ||
                    hidden.contains("owner:" + owner))
                return false;
        }
        return true;
    }
    
    private void addStyle(String typeid, String worldid, AreaMarker m, Field field) {
        AreaStyle as = cusstyle.get(worldid + "/" + typeid);
        if(as == null) {
            as = cusstyle.get(typeid);
        }
        if(as == null) {    /* Check for wildcard style matches */
            for(String wc : cuswildstyle.keySet()) {
                String[] tok = wc.split("\\|");
                if((tok.length == 1) && typeid.startsWith(tok[0]))
                    as = cuswildstyle.get(wc);
                else if((tok.length >= 2) && typeid.startsWith(tok[0]) && typeid.endsWith(tok[1]))
                    as = cuswildstyle.get(wc);
            }
        }
        if(as == null) {    /* Check for owner style matches */
            if(ownerstyle.isEmpty() != true) {
                String owner = field.getOwner();
                as = ownerstyle.get(owner.toLowerCase());
            }
        }
        if(as == null)
            as = defstyle;

        int sc = 0xFF0000;
        int fc = 0xFF0000;
        try {
            sc = Integer.parseInt(as.strokecolor.substring(1), 16);
            fc = Integer.parseInt(as.fillcolor.substring(1), 16);
        } catch (NumberFormatException nfx) {
        }
        m.setLineStyle(as.strokeweight, as.strokeopacity, sc);
        m.setFillStyle(as.fillopacity, fc);
        if(as.label != null) {
            m.setLabel(as.label);
        }
    }
    
    /* Handle specific force field */
    private void handleField(World world, Field field, Map<String, AreaMarker> newmap) {
        String name = field.getSettings().getTitle() + "[" + field.getOwner() + "]";
        double[] x = null;
        double[] z = null;
                
        /* Handle areas */
        if(isVisible(field.getSettings().getTitle(), field.getOwner(), world.getName())) {
            String markerid = world.getName() + "_" + field.getType() + "_" + field.getId();
            x = new double[] { field.getMinx(), field.getMaxx() };
            z = new double[] { field.getMinz(), field.getMaxz() };
            AreaMarker m = resareas.remove(markerid); /* Existing area? */
            if(m == null) {
                m = set.createAreaMarker(markerid, name, false, world.getName(), x, z, false);
                if(m == null)
                    return;
            }
            else {
                m.setCornerLocations(x, z); /* Replace corner locations */
                m.setLabel(name);   /* Update label */
            }
            if(use3d) { /* If 3D? */
                m.setRangeY(field.getMiny(), field.getMaxy());
            }       
            else {
                m.setRangeY(field.getY(), field.getY());
            }
            /* Set line and fill properties */
            addStyle(field.getSettings().getTitle(), world.getName(), m, field);

            /* Build popup */
            String desc = formatInfoWindow(field, m);

            m.setDescription(desc); /* Set popup */

            /* Add to map */
            newmap.put(markerid, m);
        }
    }
    
    /* Update preciousstones force fields information */
    private void updateForceFields() {
        Map<String,AreaMarker> newmap = new HashMap<String,AreaMarker>(); /* Build new map */
 
        ForceFieldManager ffm = ps.getForceFieldManager();
        /* Loop through worlds */
        for(World w : getServer().getWorlds()) {
            List<Field> ff = ffm.getFields("*", w);
            for(Field fld : ff) {
                handleField(w, fld, newmap);
            }
        }
        /* Now, review old map - anything left is gone */
        for(AreaMarker oldm : resareas.values()) {
            oldm.deleteMarker();
        }
        /* And replace with new map */
        resareas = newmap;
        
        getServer().getScheduler().scheduleSyncDelayedTask(this, new PreciousStonesUpdate(), updperiod);
        
    }

    private class OurServerListener implements Listener {
        @SuppressWarnings("unused")
        @EventHandler(priority=EventPriority.MONITOR)
        public void onPluginEnable(PluginEnableEvent event) {
            Plugin p = event.getPlugin();
            String name = p.getDescription().getName();
            if(name.equals("dynmap") || name.equals("PreciousStones")) {
                if(dynmap.isEnabled() && ps.isEnabled())
                    activate();
            }
        }
    }
    
    public void onEnable() {
        info("initializing");
        PluginManager pm = getServer().getPluginManager();
        /* Get dynmap */
        dynmap = pm.getPlugin("dynmap");
        if(dynmap == null) {
            severe("Cannot find dynmap!");
            return;
        }
        api = (DynmapAPI)dynmap; /* Get API */
        /* Get PreciousStones */
        Plugin p = pm.getPlugin("PreciousStones");
        if(p == null) {
            severe("Cannot find PreciousStones!");
            return;
        }
        ps = (PreciousStones)p;

        getServer().getPluginManager().registerEvents(new OurServerListener(), this);        
        /* If both enabled, activate */
        if(dynmap.isEnabled() && ps.isEnabled())
            activate();
    }

    private void activate() {
        /* Now, get markers API */
        markerapi = api.getMarkerAPI();
        if(markerapi == null) {
            severe("Error loading dynmap marker API!");
            return;
        }
        /* Load configuration */
        FileConfiguration cfg = getConfig();
        cfg.options().copyDefaults(true);   /* Load defaults, if needed */
        this.saveConfig();  /* Save updates, if needed */
        
        /* Now, add marker set for mobs (make it transient) */
        set = markerapi.getMarkerSet("preciousstones.markerset");
        if(set == null)
            set = markerapi.createMarkerSet("preciousstones.markerset", cfg.getString("layer.name", "PreciousStones"), null, false);
        else
            set.setMarkerSetLabel(cfg.getString("layer.name", "PreciousStones"));
        if(set == null) {
            severe("Error creating marker set");
            return;
        }
        int minzoom = cfg.getInt("layer.minzoom", 0);
        if(minzoom > 0)
            set.setMinZoom(minzoom);
        set.setLayerPriority(cfg.getInt("layer.layerprio", 10));
        set.setHideByDefault(cfg.getBoolean("layer.hidebydefault", false));
        use3d = cfg.getBoolean("use3dfields", false);
        infowindow = cfg.getString("infowindow", DEF_INFOWINDOW);
        maxdepth = cfg.getInt("maxdepth", 16);

        /* Get style information */
        defstyle = new AreaStyle(cfg, "fieldstyle");
        cusstyle = new HashMap<String, AreaStyle>();
        ownerstyle = new HashMap<String, AreaStyle>();
        cuswildstyle = new HashMap<String, AreaStyle>();
        ConfigurationSection sect = cfg.getConfigurationSection("custstyle");
        if(sect != null) {
            Set<String> ids = sect.getKeys(false);
            
            for(String id : ids) {
                if(id.indexOf('|') >= 0)
                    cuswildstyle.put(id, new AreaStyle(cfg, "custstyle." + id, defstyle));
                else
                    cusstyle.put(id, new AreaStyle(cfg, "custstyle." + id, defstyle));
            }
        }
        sect = cfg.getConfigurationSection("ownerstyle");
        if(sect != null) {
            Set<String> ids = sect.getKeys(false);
            
            for(String id : ids) {
                ownerstyle.put(id.toLowerCase(), new AreaStyle(cfg, "ownerstyle." + id, defstyle));
            }
        }
        List<String> vis = cfg.getStringList("visiblefields");
        if(vis != null) {
            visible = new HashSet<String>(vis);
        }
        List<String> hid = cfg.getStringList("hiddenfields");
        if(hid != null) {
            hidden = new HashSet<String>(hid);
        }

        /* Set up update job - based on periond */
        int per = cfg.getInt("update.period", 300);
        if(per < 15) per = 15;
        updperiod = (long)(per*20);
        stop = false;
        
        getServer().getScheduler().scheduleSyncDelayedTask(this, new PreciousStonesUpdate(), 40);   /* First time is 2 seconds */
        
        info("version " + this.getDescription().getVersion() + " is activated");
    }

    public void onDisable() {
        if(set != null) {
            set.deleteMarkerSet();
            set = null;
        }
        resareas.clear();
        stop = true;
    }

}
