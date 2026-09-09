// Copyright 2026 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.rendering.sphere;

import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.terasology.context.annotation.API;

/**
 * A world whose geometry is not the grid it is stored in.
 * <p>
 * A world generator that lays its terrain out on something other than a plane publishes an
 * implementation of this into the context, and the renderer bends its vertices to match. Nothing
 * happens without one: absent from the context, every shader keeps its plain model-view transform,
 * bit for bit.
 * <p>
 * This interface is the whole coupling between such a generator and the renderer. It carries no
 * opinion about what the shape is — a cube net, a torus, a ring — only about what the renderer
 * needs to ask of it.
 */
@API
public interface SphereProjection {

    /** The radius of the surface, in blocks. */
    float getRadius();

    /** The height that maps onto the radius: terrain at this height sits on the surface. */
    float getReferenceHeight();

    /**
     * The point the deformation is measured from, in world coordinates.
     * <p>
     * Curved positions are returned relative to it, so it should follow the camera: everything near
     * the focus keeps full precision, and everything far from it curves away.
     */
    Vector3fc getFocus();

    /**
     * The name of the OpenGL texture holding the projection table, uploading it first if needed.
     * <p>
     * Must be called on the thread holding the GL context.
     */
    int getTableTextureId();

    /** Side of the square projection table. */
    int getTableResolution();

    /** Edge of one face of the layout, in blocks. */
    float getFaceEdge();

    /**
     * Where a world position ends up once the world is bent, relative to {@link #getFocus()}.
     * <p>
     * The renderer only needs this for whole chunks, never per vertex — the shader does the per
     * vertex work from the same table. It is here so that culling, picking and any other CPU-side
     * question can be asked in the same space the GPU draws in.
     */
    Vector3f curvedPosition(Vector3fc worldPosition, Vector3f dest);

    /**
     * The local frame at a world position: which way is east, up and north once the world is bent.
     * <p>
     * Needed by anything drawn as a rigid body rather than as a field of vertices. A model bent
     * vertex by vertex would tear apart wherever it straddles a seam, so the frame is taken once,
     * at the model's own origin, and the model is placed rigidly in it. The error that leaves is
     * the sag across the model itself: a twentieth of a block for something five blocks wide.
     */
    Matrix3f frameAt(Vector3fc worldPosition, Matrix3f dest);

    /**
     * How far one can see from a height above the reference level, in blocks along the surface.
     * <p>
     * On a curved world this is a real limit rather than a setting, and it is usually much shorter
     * than the view distance.
     */
    float horizon(float heightAboveReference);
}
