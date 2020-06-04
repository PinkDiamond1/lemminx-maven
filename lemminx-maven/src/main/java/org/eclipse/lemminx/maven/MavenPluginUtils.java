/*******************************************************************************
 * Copyright (c) 2020 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.maven;

import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Repository;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.InvalidPluginDescriptorException;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.PluginDescriptorParsingException;
import org.apache.maven.plugin.PluginResolutionException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.Parameter;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RemoteRepository.Builder;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.services.extensions.IPositionRequest;
import org.eclipse.lsp4j.MarkupContent;

public class MavenPluginUtils {
	
	static final String LINE_BREAK = "\n\n";

	private MavenPluginUtils() {
		// Utility class, not meant to be instantiated
	}

	public static MarkupContent getMarkupDescription(Parameter parameter) {
		return new MarkupContent("markdown",
				"**required:** " + parameter.getRequirement() + LINE_BREAK + "**Type:** " + parameter.getType() + LINE_BREAK
						+ "Expression: " + parameter.getExpression() + LINE_BREAK + "Default Value: " + parameter.getDefaultValue()
						+ LINE_BREAK + parameter.getDescription());
	}
	
	// TODO: Handle the fact that MojoParameter's content (eg. DefaultValue) can be null
	public static MarkupContent getMarkupDescription(MojoParameter parameter) {
		return new MarkupContent("markdown",
				"**required:** " + parameter.isRequired() + LINE_BREAK + "**Type:** " + parameter.getType() + LINE_BREAK
						+ "Expression: " + parameter.getExpression() + LINE_BREAK + "Default Value: " + parameter.getDefaultValue()
						+ LINE_BREAK + parameter.getDescription());
	}
	
	// TODO: fix code duplication..
	// TODO: add a note about using description and default valuefrom parent
	public static MarkupContent getMarkupDescriptionUsingParent(MojoParameter parameter, MojoParameter parentParameter) {
		return new MarkupContent("markdown",
				"**required:** " + parameter.isRequired() + LINE_BREAK + "**Type:** " + parameter.getType() + LINE_BREAK
						+ "Expression: " + parameter.getExpression() + LINE_BREAK + "Default Value: " + parentParameter.getDefaultValue()
						+ LINE_BREAK + parentParameter.getDescription());
	}

	public static List<Parameter> collectPluginConfigurationParameters(IPositionRequest request,
			MavenProjectCache cache, RepositorySystemSession repoSession, MavenPluginManager pluginManager, BuildPluginManager buildPluginManager, MavenSession mavenSession) throws PluginResolutionException, PluginDescriptorParsingException, InvalidPluginDescriptorException {
		PluginDescriptor pluginDescriptor = MavenPluginUtils.getContainingPluginDescriptor(request, cache, repoSession,
				pluginManager);
		if (pluginDescriptor == null) {
			return Collections.emptyList();
		}
		List<MojoDescriptor> mojosToConsiderList = pluginDescriptor.getMojos();
		DOMNode executionElementDomNode = DOMUtils.findClosestParentNode(request, "execution");
		if (executionElementDomNode != null) {
			Set<String> interestingMojos = executionElementDomNode.getChildren().stream()
					.filter(node -> "goals".equals(node.getLocalName())).flatMap(node -> node.getChildren().stream())
					.filter(node -> "goal".equals(node.getLocalName())).flatMap(node -> node.getChildren().stream())
					.filter(DOMNode::isText).map(DOMNode::getTextContent).collect(Collectors.toSet());
			mojosToConsiderList = mojosToConsiderList.stream().filter(mojo -> interestingMojos.contains(mojo.getGoal()))
					.collect(Collectors.toList());
		}
		List<Parameter> parameters = mojosToConsiderList.stream().flatMap(mojo -> mojo.getParameters().stream())
				.collect(Collectors.toList());
		return parameters;
	}
	
	
	public static Set<MojoParameter> collectPluginConfigurationMojoParameters(IPositionRequest request,
			MavenProjectCache cache, RepositorySystemSession repoSession, MavenPluginManager pluginManager, BuildPluginManager buildPluginManager, MavenSession mavenSession) throws PluginResolutionException, PluginDescriptorParsingException, InvalidPluginDescriptorException {
		PluginDescriptor pluginDescriptor = MavenPluginUtils.getContainingPluginDescriptor(request, cache, repoSession,
				pluginManager);
		if (pluginDescriptor == null) {
			return Collections.emptySet();
		}
		List<MojoDescriptor> mojosToConsiderList = pluginDescriptor.getMojos();
		DOMNode executionElementDomNode = DOMUtils.findClosestParentNode(request, "execution");
		if (executionElementDomNode != null) {
			Set<String> interestingMojos = executionElementDomNode.getChildren().stream()
					.filter(node -> "goals".equals(node.getLocalName())).flatMap(node -> node.getChildren().stream())
					.filter(node -> "goal".equals(node.getLocalName())).flatMap(node -> node.getChildren().stream())
					.filter(DOMNode::isText).map(DOMNode::getTextContent).collect(Collectors.toSet());
			mojosToConsiderList = mojosToConsiderList.stream().filter(mojo -> interestingMojos.contains(mojo.getGoal()))
					.collect(Collectors.toList());
		}
		MavenProject project =  cache.getLastSuccessfulMavenProject(request.getXMLDocument());
		if (project == null) {
			return Collections.emptySet();
		}
		project.setExtensionDependencyFilter(new DependencyFilter() {
			
			@Override
			public boolean accept(DependencyNode node, List<DependencyNode> parents) {
				// TODO Auto-generated method stub
				System.out.println("Dependency: " + node.getDependency().getArtifact().getArtifactId());
				System.out.println(node.getRepositories());
				return true;
			}
		});
		mavenSession.setProjects(Collections.singletonList(project));
		Set<MojoParameter> mojoParams = mojosToConsiderList.stream().flatMap(mojo -> PlexusConfigHelper.loadMojoParameters(pluginDescriptor, mojo, mavenSession, buildPluginManager).stream()
		).collect(Collectors.toSet());
		
		return mojoParams;
	}


	public static RemoteRepository toRemoteRepo(Repository modelRepo) {
		Builder builder = new RemoteRepository.Builder(modelRepo.getId(), modelRepo.getLayout(), modelRepo.getLayout());
		return builder.build();
	}

	public static PluginDescriptor getContainingPluginDescriptor(IPositionRequest request, MavenProjectCache cache, RepositorySystemSession repositorySystemSession,
			MavenPluginManager pluginManager) throws PluginResolutionException, PluginDescriptorParsingException, InvalidPluginDescriptorException {
		MavenProject project = cache.getLastSuccessfulMavenProject(request.getXMLDocument());
		if (project == null) {
			return null;
		}
		DOMNode pluginNode = DOMUtils.findClosestParentNode(request, "plugin");
		if (pluginNode == null) {
			return null;
		}
		Optional<String> groupId = DOMUtils.findChildElementText(pluginNode, "groupId");
		Optional<String> artifactId = DOMUtils.findChildElementText(pluginNode, "artifactId");
		String pluginKey = "";
		if (groupId.isPresent()) {
			pluginKey += groupId.get();
			pluginKey += ':';
		}
		if (artifactId.isPresent()) {
			pluginKey += artifactId.get();
		}
		Plugin plugin = project.getPlugin(pluginKey);
		if (plugin == null && project.getPluginManagement() != null) {
			plugin = project.getPluginManagement().getPluginsAsMap().get(pluginKey);
			
			if (plugin == null && artifactId.isPresent()) {
				//pluginArtifactMap will be empty if PluginManagement is null
				for (Entry <String, Artifact> entry : project.getPluginArtifactMap().entrySet() ) {
					if (entry.getValue().getArtifactId().equals(artifactId.get())) {
						plugin = project.getPlugin(entry.getKey());
					}
				}
			}
		}
		
		if (plugin == null) {
			throw new InvalidPluginDescriptorException("Unable to resolve " + pluginKey,  Collections.emptyList());
		}

		return pluginManager.getPluginDescriptor(plugin, project.getPluginRepositories().stream()
				.map(MavenPluginUtils::toRemoteRepo).collect(Collectors.toList()), repositorySystemSession);
	}

}
