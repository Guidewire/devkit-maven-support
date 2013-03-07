package com.guidewire.build.ijdevkitmvn.ijplugin;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.LibraryOrderEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.impl.libraries.ProjectLibraryTable;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Processor;
import gnu.trove.THashSet;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.devkit.build.PluginBuildConfiguration;
import org.jetbrains.idea.devkit.module.PluginModuleType;
import org.jetbrains.idea.maven.importing.MavenImporter;
import org.jetbrains.idea.maven.importing.MavenModifiableModelsProvider;
import org.jetbrains.idea.maven.importing.MavenRootModelAdapter;
import org.jetbrains.idea.maven.model.MavenConstants;
import org.jetbrains.idea.maven.model.MavenPlugin;
import org.jetbrains.idea.maven.project.MavenConsole;
import org.jetbrains.idea.maven.project.MavenEmbeddersManager;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectChanges;
import org.jetbrains.idea.maven.project.MavenProjectsProcessorTask;
import org.jetbrains.idea.maven.project.MavenProjectsTree;
import org.jetbrains.idea.maven.project.SupportedRequestType;
import org.jetbrains.idea.maven.utils.MavenProcessCanceledException;
import org.jetbrains.idea.maven.utils.MavenProgressIndicator;
import org.jetbrains.idea.maven.utils.MavenUtil;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 */
public class PluginModuleImporter extends MavenImporter {

  public static final String IJ_PLUGIN_PACKAGING = "ij-plugin";
  public static final String IDEA_SDK_PREFIX = "com.jetbrains.intellij.";
  public static final String IJPLUGIN_GROUP_ID = "com.guidewire.build";
  public static final String IJPLUGIN_ARTIFACT_ID = "ijplugin-maven-plugin";
  public static final String IJPLUGIN_PROPERTY = "ij.plugin";
  public static final String IJPLUGIN_DESCRIPTOR_PROPERTY = "ij.pluginDescriptor";
  public static final String MANIFEST_LOCATION_PARAMETER = "manifestLocation";


  public PluginModuleImporter() {
    super(IJPLUGIN_GROUP_ID, IJPLUGIN_ARTIFACT_ID);
  }

  @Override
  public void preProcess(Module module, MavenProject mavenProject, MavenProjectChanges mavenProjectChanges, MavenModifiableModelsProvider mavenModifiableModelsProvider) {
  }

  @Override
  public void process(MavenModifiableModelsProvider modelsProvider,
                      Module module,
                      MavenRootModelAdapter mavenRootModelAdapter,
                      MavenProjectsTree mavenProjectsTree,
                      MavenProject mavenProject,
                      MavenProjectChanges mavenProjectChanges,
                      Map<MavenProject, String> mavenProjectStringMap,
                      List<MavenProjectsProcessorTask> mavenProjectsProcessorTasks) {

    // Remove all entries with groupIds starting from com.jetbrains.intellij.
    // They should be provided through IntelliJ SDK
    ModifiableRootModel rootModel = mavenRootModelAdapter.getRootModel();
    List<Library> libraries = new ArrayList<Library>();

    for (OrderEntry entry : rootModel.getOrderEntries()) {
      if (entry instanceof LibraryOrderEntry) {
        LibraryOrderEntry loe = (LibraryOrderEntry) entry;
        String libraryName = loe.getLibraryName();
        if (libraryName != null && libraryName.startsWith("Maven: " + IDEA_SDK_PREFIX)) {
          rootModel.removeOrderEntry(entry);
          libraries.add(loe.getLibrary());
        }
      }
    }

    updateManifestLocation(module, mavenProject);

    // Workaround for Maven plugin bug which does not allow newly created unused libraries to be properly removed
    // We schedule our own post-processing task that will clean out unused libraries
    scheduleAnalyzeLibraryTask(mavenProjectsProcessorTasks, libraries);
  }

  private void updateManifestLocation(Module module, MavenProject mavenProject) {
    String manifestLocation = findManifestLocation(mavenProject);
    PluginBuildConfiguration config = PluginBuildConfiguration.getInstance(module);
    if (config != null) {
      VirtualFile basedir = mavenProject.getDirectoryFile();
      VirtualFile pluginXml = basedir.findFileByRelativePath(manifestLocation);
      if (pluginXml != null && pluginXml.exists()) {
        setPluginXmlPath(config, pluginXml.getPath());
      }
    }
  }

  private void setPluginXmlPath(PluginBuildConfiguration config, String path) {
    try {
      Method m;
      try {
        // IntelliJ 12
        m = config.getClass().getMethod("setPluginXmlPathAndCreateDescriptorIfDoesntExist", String.class);
      } catch (NoSuchMethodException e) {
        // IntelliJ 11
        m = config.getClass().getMethod("setPluginXmlPath", String.class);
      }

      m.invoke(config, path);
    } catch (Exception e) {
      // No big deal, let's not set it.
    }
  }

  private String findManifestLocation(MavenProject mavenProject) {
    String manifestLocation = null;

    // Try ijplugin-maven-plugin configuration
    MavenPlugin plugin =
            mavenProject.findPlugin(myPluginGroupID, myPluginArtifactID);
    if (plugin != null) {
      Element config = plugin.getConfigurationElement();
      if (config != null) {
        Element child = config.getChild(MANIFEST_LOCATION_PARAMETER);
        manifestLocation = child.getText();
      }
    }

    // Try ij.pluginDescriptor
    if (manifestLocation == null) {
      manifestLocation = mavenProject.getProperties().getProperty(IJPLUGIN_DESCRIPTOR_PROPERTY);
    }

    if (manifestLocation == null) {
      // Default location
      manifestLocation = "META-INF/plugin.xml";
    }
    return manifestLocation;
  }

  private void scheduleAnalyzeLibraryTask(List<MavenProjectsProcessorTask> mavenProjectsProcessorTasks, List<Library> libraries) {
    // First, look if task is in the list already
    RemoveUnusedLibrariesTask task = null;
    for (MavenProjectsProcessorTask t : mavenProjectsProcessorTasks) {
      if (t instanceof RemoveUnusedLibrariesTask) {
        task = (RemoveUnusedLibrariesTask) t;
        break;
      }
    }

    // Create new task
    if (task == null) {
      task = new RemoveUnusedLibrariesTask();
      mavenProjectsProcessorTasks.add(task);
    }

    // Schedule task
    task.addLibaries(libraries);
  }

  private static class RemoveUnusedLibrariesTask implements MavenProjectsProcessorTask {
    private final Set<Library> _libraries = new THashSet<Library>();

    public void addLibaries(Collection<Library> libraries) {
      _libraries.addAll(libraries);
    }

    @Override
    public void perform(Project project, MavenEmbeddersManager embeddersManager, MavenConsole console, MavenProgressIndicator indicator) throws MavenProcessCanceledException {
      if (!_libraries.isEmpty()) {
        removeUnusedProjectLibraries(project);
      }
    }

    private void removeUnusedProjectLibraries(Project project) {
      final Set<Library> unusedLibraries = new THashSet<Library>(_libraries);
      ModuleManager moduleManager = ModuleManager.getInstance(project);
      for (Module m : moduleManager.getModules()) {
        OrderEnumerator.orderEntries(m).forEach(new Processor<OrderEntry>() {
          @Override
          public boolean process(OrderEntry orderEntry) {
            if (orderEntry instanceof LibraryOrderEntry) {
              Library lib = ((LibraryOrderEntry) orderEntry).getLibrary();
              unusedLibraries.remove(lib);
            }
            return true;
          }
        });
      }

      // Remove all unused libraries
      if (!unusedLibraries.isEmpty()) {
        final LibraryTable.ModifiableModel rootModel = ProjectLibraryTable.getInstance(project).getModifiableModel();
        for (Library lib : unusedLibraries) {
          rootModel.removeLibrary(lib);
        }
        MavenUtil.invokeAndWaitWriteAction(project, new Runnable() {
          @Override
          public void run() {
            rootModel.commit();
          }
        });
      }
    }
  }

  /**
   * Enable importer for JAR projects with plugin attached to the lifecycle or for the projects
   * explicitly marked with "ij-plugin" packaging.
   */
  @Override
  public boolean isApplicable(MavenProject mavenProject) {
    String type = mavenProject.getPackaging();
    if (IJ_PLUGIN_PACKAGING.equals(type)) {
      return true;
    } else if (MavenConstants.TYPE_JAR.equals(type)) {
      return super.isApplicable(mavenProject) ||
              Boolean.valueOf(mavenProject.getProperties().getProperty(IJPLUGIN_PROPERTY));
    }
    return false;
  }

  @Override
  public void getSupportedPackagings(Collection<String> result) {
    result.add(IJ_PLUGIN_PACKAGING);
    result.add(MavenConstants.TYPE_JAR);
  }

  @Override
  public void getSupportedDependencyTypes(Collection<String> result, SupportedRequestType type) {
    result.add(IJ_PLUGIN_PACKAGING);
    result.add(MavenConstants.TYPE_JAR);
  }

  @NotNull
  @Override
  public ModuleType getModuleType() {
    return PluginModuleType.getInstance();
  }
}
