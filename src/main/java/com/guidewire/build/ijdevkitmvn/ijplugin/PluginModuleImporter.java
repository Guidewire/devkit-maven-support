package com.guidewire.build.ijdevkitmvn.ijplugin;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.roots.LibraryOrderEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.impl.libraries.LibraryEx;
import com.intellij.openapi.roots.libraries.Library;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.devkit.module.PluginModuleType;
import org.jetbrains.idea.maven.importing.MavenImporter;
import org.jetbrains.idea.maven.importing.MavenModifiableModelsProvider;
import org.jetbrains.idea.maven.importing.MavenRootModelAdapter;
import org.jetbrains.idea.maven.model.MavenArtifact;
import org.jetbrains.idea.maven.model.MavenConstants;
import org.jetbrains.idea.maven.project.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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

    for (OrderEntry entry : rootModel.getOrderEntries()) {
      if (entry instanceof LibraryOrderEntry) {
        LibraryOrderEntry loe = (LibraryOrderEntry) entry;
        if (loe.getLibraryName().startsWith("Maven: com.jetbrains.intellij.")) {
          rootModel.removeOrderEntry(entry);

          // XXX: If library is not used anymore, it will get properly commited. As a result,
          // roots of type CLASSES will be empty. MavenProjectImporter.removeUnusedProjectLibraries
          // will treat such a library as having "user changes" and will not remove it.
          // Therefore, we forcefully commit all changes.
          Library.ModifiableModel modifiableModel = modelsProvider.getLibraryModel(loe.getLibrary());
          if (!((LibraryEx) modifiableModel).isDisposed()) {
            modifiableModel.commit();
          }
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
