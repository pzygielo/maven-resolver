/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.eclipse.aether.util.graph.version;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.collection.DependencyCollectionContext;
import org.eclipse.aether.collection.VersionFilter;

/**
 * A version filter that blocks "*-SNAPSHOT" versions if the
 * {@link VersionFilterContext#getDependency()} ancestor whose range is being filtered is not a snapshot.
 */
public class ContextualAncestorSnapshotVersionFilter implements VersionFilter {
    private final SnapshotVersionFilter filter;

    /**
     * Creates a new instance of this version filter.
     */
    public ContextualAncestorSnapshotVersionFilter() {
        filter = new SnapshotVersionFilter();
    }

    @Override
    public void filterVersions(VersionFilterContext context) {
        Artifact ancestor = context.getDependency().getArtifact();
        if (!ancestor.isSnapshot()) {
            filter.filterVersions(context);
        }
    }

    @Override
    public VersionFilter deriveChildFilter(DependencyCollectionContext context) {
        return this;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        } else if (null == obj || !getClass().equals(obj.getClass())) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
