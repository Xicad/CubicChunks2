package io.github.opencubicchunks.cubicchunks.chunk;

import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cubicchunks.chunk.cube.BigCube;

public class ClientChunkProviderCubeArray {

    public final AtomicReferenceArray<BigCube> cubes;
    public final int horizontalViewDistance;
    public final int verticalViewDistance;
    public volatile int centerX;
    public volatile int centerY;
    public volatile int centerZ;
    public int loaded;

    private final int horizontalSideLength;
    private final int verticalSideLength;
    private final int sideArea;
    private final Consumer<BigCube> onUnload;

    public ClientChunkProviderCubeArray(int horizontalViewDistance, int verticalViewDistance, Consumer<BigCube> onUnload) {
        this.horizontalViewDistance = horizontalViewDistance;
        this.verticalViewDistance = verticalViewDistance;
        this.horizontalSideLength = horizontalViewDistance * 2 + 1;
        this.verticalSideLength = verticalViewDistance * 2 + 1;
        this.sideArea = this.horizontalSideLength * this.horizontalSideLength;
        this.onUnload = onUnload;
        this.cubes = new AtomicReferenceArray<>(this.horizontalSideLength * this.verticalSideLength * this.horizontalSideLength);
    }

    public int getIndex(int x, int y, int z) {
        return Math.floorMod(y, this.verticalSideLength) * this.sideArea
            + Math.floorMod(z, this.horizontalSideLength) * this.horizontalSideLength
            + Math.floorMod(x, this.horizontalSideLength);
    }

    public void replace(int cubeIdx, @Nullable BigCube chunkIn) {
        BigCube cube = this.cubes.getAndSet(cubeIdx, chunkIn);
        if (cube != null) {
            --this.loaded;
            onUnload.accept(cube);
        }

        if (chunkIn != null) {
            ++this.loaded;
        }

    }

    public BigCube unload(int chunkIndex, BigCube chunkIn, @Nullable BigCube replaceWith) {
        if (this.cubes.compareAndSet(chunkIndex, chunkIn, replaceWith) && replaceWith == null) {
            --this.loaded;
        }

        onUnload.accept(chunkIn);
        return chunkIn;
    }

    public boolean inView(int x, int y, int z) {
        return Math.abs(x - this.centerX) <= this.horizontalViewDistance
            && Math.abs(y - this.centerY) <= this.verticalViewDistance
            && Math.abs(z - this.centerZ) <= this.horizontalViewDistance;
    }

    @Nullable
    public BigCube get(int chunkIndex) {
        return this.cubes.get(chunkIndex);
    }
}