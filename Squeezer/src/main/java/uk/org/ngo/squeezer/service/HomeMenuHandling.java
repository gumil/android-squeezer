package uk.org.ngo.squeezer.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;

import uk.org.ngo.squeezer.model.JiveItem;
import uk.org.ngo.squeezer.model.MenuStatusMessage;

public class HomeMenuHandling {

    /** Home menu tree as received from slimserver */
    public final List<JiveItem> homeMenu = new Vector<>();

    private List<JiveItem> resetHomeMenu(List<JiveItem> items) {
        homeMenu.clear();
        homeMenu.addAll(items);
        return homeMenu; // return it to ConnectionState
    }

    boolean checkIfItemIsAlreadyInArchive(JiveItem toggledItem) {
        if (getParents(toggledItem.getNode()).contains(JiveItem.ARCHIVE)) {
//            TODO: Message to the user
            return Boolean.TRUE;
        }
        else {
            return Boolean.FALSE;
        }
    }

    void cleanupArchive(JiveItem toggledItem) {
        for (JiveItem archiveItem : homeMenu) {
            if (archiveItem.getNode().equals(JiveItem.ARCHIVE.getId())) {
                Set<JiveItem> parents = getOriginalParents(archiveItem.getOriginalNode());
                if ( parents.contains(toggledItem)) {
                    archiveItem.setNode(archiveItem.getOriginalNode());
                }
            }
        }
    }

    public void handleMenuStatusEvent(MenuStatusMessage event) {
        for (JiveItem menuItem : event.menuItems) {
            JiveItem item = null;
            for (JiveItem menu : homeMenu) {
                if (menuItem.getId().equals(menu.getId())) {
                    item = menu;
                    break;
                }
            }
            if (item != null) {
                homeMenu.remove(item);
            }
            if (MenuStatusMessage.ADD.equals(event.menuDirective)) {
                homeMenu.add(menuItem);
            }
        }
    }

    public Set<JiveItem> getOriginalParents(String node) {
        Set<JiveItem> parents = new HashSet<>();
        getParents(node, parents, JiveItem::getOriginalNode);
        return parents;
    }

    private Set<JiveItem> getParents(String node) {
        Set<JiveItem> parents = new HashSet<>();
        getParents(node, parents, JiveItem::getNode);
        return parents;
    }

    private void getParents(String node, Set<JiveItem> parents, HomeMenuHandling.GetParent getParent) {
        if (node.equals(JiveItem.HOME.getId())) {          // if we are done
            return;
        }
        for (JiveItem menuItem : homeMenu) {
            if (menuItem.getId().equals(node)) {
                String parent = getParent.getNode(menuItem);
                parents.add(menuItem);
                getParents(parent, parents, getParent);
            }
        }
    }

    private interface GetParent {
        String getNode(JiveItem item);
    }

    public List<String> getArchivedItems() {
        List<String> archivedItems = new ArrayList<>();
        for (JiveItem item : homeMenu) {
            if (item.getNode().equals(JiveItem.ARCHIVE.getId())) {
                archivedItems.add(item.getId());
            }
        }
        return archivedItems;
    }

    public List<JiveItem> setHomeMenu(List<JiveItem> homeMenu, List<String> archivedItems) {
        jiveMainNodes(homeMenu);
        if (!(archivedItems.isEmpty()) && (!homeMenu.contains(JiveItem.ARCHIVE))) {
            homeMenu.add(JiveItem.ARCHIVE);
        }
        for (String s : archivedItems) {
            for (JiveItem item : homeMenu) {
                if  (item.getId().equals(s)) {
                    item.setNode(JiveItem.ARCHIVE.getId());
                }
            }
        }
        return resetHomeMenu(homeMenu);
    }

    private void jiveMainNodes(List<JiveItem> homeMenu) {
        addNode(JiveItem.EXTRAS, homeMenu);
        addNode(JiveItem.SETTINGS, homeMenu);
        addNode(JiveItem.ADVANCED_SETTINGS, homeMenu);
    }

    private void addNode(JiveItem jiveItem, List<JiveItem> homeMenu) {
        if (!homeMenu.contains(jiveItem))
            homeMenu.add(jiveItem);
    }
}
