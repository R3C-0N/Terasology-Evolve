// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.rendering.logic;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.joml.Vector3f;
import org.terasology.engine.entitySystem.Owns;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.network.Replicate;
import org.terasology.engine.rendering.assets.animation.MeshAnimation;
import org.terasology.engine.rendering.assets.material.Material;
import org.terasology.engine.rendering.assets.skeletalmesh.SkeletalMesh;
import org.terasology.engine.world.block.ForceBlockActive;
import org.terasology.nui.Color;
import org.terasology.nui.properties.Range;

import java.util.List;
import java.util.Map;

@ForceBlockActive
public class SkeletalMeshComponent implements VisualComponent<SkeletalMeshComponent> {
    @Replicate
    public SkeletalMesh mesh;

    @Replicate
    public Material material;

    /**
     * Should not be set manually. Stores the data of the selected animation variation.
     */
    @Replicate
    public MeshAnimation animation;

    /**
     * If true, an animation from {@link #animationPool} will be played when the current animation is done.
     */
    @Replicate
    public boolean loop;

    /**
     * When the current animation is done and loop is true then a random animation will be picked from this pool of
     * animations.
     */
    @Replicate
    public List<MeshAnimation> animationPool = Lists.newArrayList();

    public float animationRate = 1.0f;
    @Range(min = -2.5f, max = 2.5f)
    public float heightOffset;

    @Owns
    public Map<String, EntityRef> boneEntities;
    public EntityRef rootBone = EntityRef.NULL;
    public float animationTime;

    @Replicate
    public Vector3f scale = new Vector3f(1, 1, 1);
    @Replicate
    public Vector3f translate = new Vector3f();

    @Replicate
    public Color color = Color.WHITE;

    /**
     * Where to read the light from, if not from where the mesh is.
     * <p>
     * Lighting is normally sampled at the entity's own position, which is right for anything that walks
     * about in the open. It is wrong for a body that ends up somewhere its lighting has no business
     * following — sinking into the ground, say, where it would sample the pitch dark inside a block and
     * render as a black silhouette. Setting this pins the sample to one world position, once, and the mesh
     * goes on being lit as it was when it was still out in the air.
     * <p>
     * Null means the ordinary behaviour. This is a fixed point, not an offset: it is written once and never
     * has to follow the entity, so it costs one replication and nothing per frame.
     */
    @Replicate
    public Vector3f lightPosition;

    @Override
    public void copyFrom(SkeletalMeshComponent other) {
        this.mesh = other.mesh;
        this.material = other.material;
        this.animation = other.animation;
        this.loop = other.loop;
        this.animationPool = Lists.newArrayList(other.animationPool);
        this.animationRate = other.animationRate;
        this.heightOffset = other.heightOffset;
        this.boneEntities = Maps.newHashMap(other.boneEntities);
        this.rootBone = other.rootBone;
        this.animationTime = other.animationTime;
        this.scale = new Vector3f(other.scale);
        this.translate = new Vector3f(other.translate);
        this.color = new Color(other.color);
        this.lightPosition = other.lightPosition == null ? null : new Vector3f(other.lightPosition);
    }
}
