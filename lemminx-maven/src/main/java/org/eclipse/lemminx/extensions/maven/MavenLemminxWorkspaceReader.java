/*******************************************************************************
 * Copyright (c) 2021 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;
import org.eclipse.aether.util.artifact.ArtifactIdUtils;

/**
 * This workspace reader allows to resolve GAV to local workspaceFolders that match
 * instead of usual Maven repos.
 */
public class MavenLemminxWorkspaceReader implements WorkspaceReader {

	private static final Logger LOGGER = Logger.getLogger(MavenLemminxExtension.class.getName());

	private final WorkspaceRepository repository;
	private final MavenLemminxExtension plugin;
	
	private ThreadLocal<Boolean> skipFlushBeforeResult = new ThreadLocal<>();
	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	private Map<Artifact, File> workspaceArtifacts = new ConcurrentHashMap<>();
	
	public MavenLemminxWorkspaceReader(MavenLemminxExtension plugin) {
		this.plugin = plugin;
		repository = new WorkspaceRepository("workspace");
		skipFlushBeforeResult.set(false);
	}

	@Override
	public WorkspaceRepository getRepository() {
		return repository;
	}

	@Override
	public File findArtifact(Artifact artifact) {
		if (skipFlushBeforeResult.get() != Boolean.TRUE) {
			waitForCompletion();
		}
		String projectKey = ArtifactUtils.key(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
		return plugin.getProjectCache().getProjects().stream()
				.filter(p -> projectKey.equals(ArtifactUtils.key(p.getArtifact())))
				.map(project -> {
					File file = find(project, artifact);
					if (file == null && project != project.getExecutionProject()) {
						file = find(project.getExecutionProject(), artifact);
					}
					return file;
				}).filter(Objects::nonNull)
				.findAny()
				.or(() -> workspaceArtifacts.entrySet().stream() //
						.filter(entry -> ArtifactUtils.key(entry.getKey().getGroupId(), entry.getKey().getArtifactId(), entry.getKey().getVersion()).equals(projectKey))
						.map(Entry::getValue)
						.findAny())
				.orElse(null);
	}
 
	@Override
	public List<String> findVersions(Artifact artifact) {
		if (skipFlushBeforeResult.get() != Boolean.TRUE) {
			waitForCompletion();
		}
		String key = ArtifactUtils.versionlessKey(artifact.getGroupId(), artifact.getArtifactId());
		SortedSet<String> res = new TreeSet<>(Comparator.reverseOrder());
		plugin.getProjectCache().getProjects().stream() //
				.filter(p -> key.equals(ArtifactUtils.versionlessKey(p.getArtifact()))) //
				.filter(p -> find(p, artifact) != null) //
				.map(MavenProject::getVersion) //
				.forEach(res::add);
		workspaceArtifacts.entrySet().stream() //
				.filter(entry -> Objects.equals(key, ArtifactUtils.versionlessKey(entry.getKey().getGroupId(), entry.getKey().getArtifactId())))
				.map(Entry::getKey)
				.map(Artifact::getVersion)
				.forEach(res::add);
		return new ArrayList<>(res);
	}
	
	private void waitForCompletion() {
		try {
			executor.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			LOGGER.severe(e.getMessage());
		}
	}

	private File find(MavenProject project, Artifact artifact) {
		if ("pom".equals(artifact.getExtension())) {
			return project.getFile();
		}

		Artifact projectArtifact = findMatchingArtifact(project, artifact);
		if (hasArtifactFileFromPackagePhase(projectArtifact)) {
			return projectArtifact.getFile();
		}
		return null;
	}
	
	private boolean hasArtifactFileFromPackagePhase(Artifact projectArtifact) {
		return projectArtifact != null && projectArtifact.getFile() != null && projectArtifact.getFile().exists();
	}
	
	private Artifact findMatchingArtifact(MavenProject project, Artifact requestedArtifact) {
		String requestedRepositoryConflictId = ArtifactIdUtils.toVersionlessId(requestedArtifact);
		Artifact mainArtifact = RepositoryUtils.toArtifact(project.getArtifact());
		if (requestedRepositoryConflictId.equals(ArtifactIdUtils.toVersionlessId(mainArtifact))) {
			return mainArtifact;
		}

		for (Artifact attachedArtifact : RepositoryUtils.toArtifacts(project.getAttachedArtifacts())) {
			if (attachedArtifactComparison(requestedArtifact, attachedArtifact)) {
				return attachedArtifact;
			}
		}
		return null;
	}
	
	private boolean attachedArtifactComparison(Artifact requested, Artifact attached) {
		return requested.getArtifactId().equals(attached.getArtifactId())
				&& requested.getGroupId().equals(attached.getGroupId())
				&& requested.getVersion().equals(attached.getVersion())
				&& requested.getExtension().equals(attached.getExtension())
				&& requested.getClassifier().equals(attached.getClassifier());
	}

	/**
	 * Parses and adds a document for a given URI into the projects cache
	 * 
	 * @param uri An URI of a document to add
	 * @param documents documents to add
	 */
	public void addToWorkspace(Collection<URI> uris) {
		// TODO also populate from parent projects is possible
		// so a safer heuristic would be to populate from leaf to root
		// to avoid computing parents multiple times
		for (URI uri : uris) {
			executor.execute(() -> {
				File file = new File(uri);
				if (workspaceArtifacts.containsValue(file)) {
					return;
				}
				skipFlushBeforeResult.set(true); // avoid deadlock
//				synchronized (MavenLemminxWorkspaceReader.this) {
				plugin.getProjectCache().getSnapshotProject(file) //
					.map(mavenProject -> new DefaultArtifact(mavenProject.getGroupId(), mavenProject.getArtifactId(), null, mavenProject.getVersion())) //
					.ifPresent(artifact -> workspaceArtifacts.put(artifact, file));
//				}
				skipFlushBeforeResult.set(false);
			});
		}
	}

	public void remove(URI uri) {
		workspaceArtifacts.values().remove(new File(uri));
	}
}
