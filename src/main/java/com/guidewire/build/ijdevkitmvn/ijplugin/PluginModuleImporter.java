package com.guidewire.build.ijdevkitmvn.ijplugin;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.roots.LibraryOrderEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.OrderEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.devkit.module.PluginModuleType;
import org.jetbrains.idea.maven.importing.MavenImporter;
import org.jetbrains.idea.maven.importing.MavenModifiableModelsProvider;
import org.jetbrains.idea.maven.importing.MavenRootModelAdapter;
import org.jetbrains.idea.maven.model.MavenConstants;
import org.jetbrains.idea.maven.project.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 */
public class PluginModuleImporter extends MavenImporter {

  public static final String IJ_PLUGIN_PACKAGING = "ij-plugin";


  public PluginModuleImporter() {
    super("com.guidewire.build", "ijplugin-maven-plugin");
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

    for (OrderEntry entry : rootModel.getOrderEntries()) {
      if (entry instanceof LibraryOrderEntry) {
        LibraryOrderEntry loe = (LibraryOrderEntry) entry;
        if (loe.getLibraryName().startsWith("Maven: com.jetbrains.intellij.")) {
          rootModel.removeOrderEntry(entry);
        }
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
  }

  @Override
  public void getSupportedDependencyTypes(Collection<String> result, SupportedRequestType type) {
    result.add(IJ_PLUGIN_PACKAGING);
  }

  @NotNull
  @Override
  public ModuleType getModuleType() {
    return PluginModuleType.getInstance();
  }
}
