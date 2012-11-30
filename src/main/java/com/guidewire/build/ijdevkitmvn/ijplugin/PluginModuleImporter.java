package com.guidewire.build.ijdevkitmvn.ijplugin;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.LibraryOrderEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ModuleRootModel;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.impl.libraries.LibraryEx;
import com.intellij.openapi.roots.impl.libraries.LibraryImpl;
import com.intellij.openapi.roots.impl.libraries.ProjectLibraryTable;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.util.Processor;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.devkit.module.PluginModuleType;
import org.jetbrains.idea.maven.importing.MavenImporter;
import org.jetbrains.idea.maven.importing.MavenModifiableModelsProvider;
import org.jetbrains.idea.maven.importing.MavenRootModelAdapter;
import org.jetbrains.idea.maven.model.MavenConstants;
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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
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
        if (loe.getLibraryName().startsWith("Maven: " + IDEA_SDK_PREFIX)) {
          rootModel.removeOrderEntry(entry);
          libraries.add(loe.getLibrary());
        }
      }
    }

    // Workaround for Maven plugin bug which does not allow newly created unused libraries to be properly removed
    // We schedule our own post-processing task that will clean out unused libraries
    scheduleAnalyzeLibraryTask(mavenProjectsProcessorTasks, libraries);
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
   *
   * @param mavenProject
   * @return
   */
  @Override
  public boolean isApplicable(MavenProject mavenProject) {
    String type = mavenProject.getPackaging();
    return (super.isApplicable(mavenProject) && MavenConstants.TYPE_JAR.equals(type)) ||
            IJ_PLUGIN_PACKAGING.equals(type);
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
