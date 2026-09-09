// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.dag.stateChanges;

import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.terasology.engine.rendering.assets.material.Material;
import org.terasology.engine.rendering.dag.StateChange;
import org.terasology.engine.rendering.sphere.SphereProjection;
import org.terasology.gestalt.assets.ResourceUrn;

import java.util.Objects;
import java.util.function.Supplier;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.terasology.engine.rendering.dag.AbstractNode.getMaterial;

/**
 * Hands a material everything it needs to bend its vertices onto a curved world.
 * <p>
 * A state change rather than a line in some node's process method, because the task list generator
 * runs it immediately before the node that asked for it, and drops it again afterwards: a node that
 * has not asked keeps the plain transform.
 * <p>
 * Two details are load-bearing. The model origin is taken through a supplier, not a value, because
 * the shadow pass moves its light camera during its own process method and a value captured at
 * construction would be a frame stale. And equality covers only the slot and the material, never
 * the numbers, so that the task list stays identical from frame to frame while the focus moves —
 * the generator indexes persistent state changes by class and would otherwise rebuild the list
 * every frame.
 */
public class SetSphereProjection implements StateChange {

    private final int textureSlot;
    private final ResourceUrn materialUrn;
    private final SphereProjection projection;
    private final Supplier<Vector3fc> modelOrigin;
    private final boolean enabled;
    private final Material material;
    private final Vector3f scratch = new Vector3f();

    private SetSphereProjection defaultInstance;

    /**
     * @param textureSlot the texture unit to bind the projection table to. Pick one the material
     *         does not already use; the chunk material takes zero through seven.
     * @param modelOrigin where the geometry's own reference point is: the camera for the main
     *         passes, the light camera for the shadow pass.
     */
    public SetSphereProjection(int textureSlot, SphereProjection projection, ResourceUrn materialUrn,
                               Supplier<Vector3fc> modelOrigin) {
        this.textureSlot = textureSlot;
        this.materialUrn = materialUrn;
        this.projection = projection;
        this.modelOrigin = modelOrigin;
        this.enabled = true;
        this.material = getMaterial(materialUrn);
    }

    private SetSphereProjection(int textureSlot, ResourceUrn materialUrn) {
        this.textureSlot = textureSlot;
        this.materialUrn = materialUrn;
        this.projection = null;
        this.modelOrigin = null;
        this.enabled = false;
        this.material = getMaterial(materialUrn);
        this.defaultInstance = this;
    }

    @Override
    public int hashCode() {
        return Objects.hash(textureSlot, materialUrn, enabled);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SetSphereProjection
                && this.textureSlot == ((SetSphereProjection) other).textureSlot
                && this.enabled == ((SetSphereProjection) other).enabled
                && this.materialUrn.equals(((SetSphereProjection) other).materialUrn);
    }

    @Override
    public StateChange getDefaultInstance() {
        if (defaultInstance == null) {
            defaultInstance = new SetSphereProjection(textureSlot, materialUrn);
        }
        return defaultInstance;
    }

    @Override
    public String toString() {
        return String.format("%30s: slot %s, material %s, %s", this.getClass().getSimpleName(),
                textureSlot, materialUrn, enabled ? "enabled" : "disabled");
    }

    @Override
    public void process() {
        // The set of desired state changes is unordered, so do not rely on EnableMaterial having
        // been processed first. Enabling twice costs nothing.
        material.enable();

        if (!enabled) {
            material.setInt("sphereEnabled", 0, true);
            glActiveTexture(GL_TEXTURE0 + textureSlot);
            glBindTexture(GL_TEXTURE_2D, 0);
            return;
        }

        glActiveTexture(GL_TEXTURE0 + textureSlot);
        glBindTexture(GL_TEXTURE_2D, projection.getTableTextureId());

        material.setInt("sphereTable", textureSlot, true);
        material.setInt("sphereEnabled", 1, true);
        material.setInt("sphereTableSize", projection.getTableResolution(), true);
        material.setFloat("sphereRadius", projection.getRadius(), true);
        material.setFloat("sphereFaceEdge", projection.getFaceEdge(), true);
        material.setFloat("sphereReferenceHeight", projection.getReferenceHeight(), true);
        material.setFloat3("sphereFocus", projection.getFocus(), true);
        material.setFloat3("sphereModelOrigin", scratch.set(modelOrigin.get()), true);
    }
}
