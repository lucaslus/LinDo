package do_.linux.lindo.buildlogic;

import org.gradle.api.artifacts.transform.*;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.*;
import org.objectweb.asm.*;

/** Version-checked artifact transform; all upstream resources and license notices are retained. */
@CacheableTransform
public abstract class MacComposeInputPatch implements TransformAction<TransformParameters.None> {
    @InputArtifact
    @PathSensitive(PathSensitivity.NONE)
    public abstract Provider<FileSystemLocation> getInputArtifact();

    @Override public void transform(TransformOutputs outputs) {
        Path source = getInputArtifact().get().getAsFile().toPath();
        try (ZipFile jar = new ZipFile(source.toFile())) {
            if (jar.getEntry(ENTRY) == null) {
                outputs.file(source.toFile());
                return;
            }
        } catch (Exception error) {
            throw new IllegalStateException("Cannot inspect desktop artifact: " + source, error);
        }
        try {
            patch(source, outputs.file("ui-desktop-macos-input.jar").toPath());
        } catch (Exception error) {
            throw new IllegalStateException("Cannot apply version-checked Compose macOS input patch", error);
        }
    }

    private static final String ENTRY = "androidx/compose/ui/scene/ComposeSceneMediator_desktopKt.class";
    private static final String ORIGINAL_SHA256 = "d8e08083c3cf696848a1b1c4ecabd6e28fd03da9e560e87819298b2540985a02";

    private static void patch(Path source, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        int patched = 0;
        try (ZipFile input = new ZipFile(source.toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temporary))) {
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] bytes;
                try (var stream = input.getInputStream(entry)) { bytes = stream.readAllBytes(); }
                if (entry.getName().equals(ENTRY)) {
                    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    if (!hash.equals(ORIGINAL_SHA256)) {
                        throw new IllegalStateException("Compose mouse bridge changed; review/rebase the macOS patch before upgrading: " + hash);
                    }
                    ClassReader reader = new ClassReader(bytes);
                    ClassWriter writer = new ClassWriter(reader, 0);
                    int[] methods = {0};
                    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                                   String signature, String[] exceptions) {
                            if (name.equals("getLockingKeyStateSafe") && descriptor.equals("(I)Z")) {
                                methods[0]++;
                                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                                method.visitCode();
                                method.visitVarInsn(Opcodes.ILOAD, 0);
                                method.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "do_/linux/lindo/MacKeyboardState", "getLockingKeyState", "(I)Z", false);
                                method.visitInsn(Opcodes.IRETURN);
                                method.visitMaxs(1, 1);
                                method.visitEnd();
                                return null;
                            }
                            return super.visitMethod(access, name, descriptor, signature, exceptions);
                        }
                    }, 0);
                    if (methods[0] != 1) throw new IllegalStateException("Expected exactly one locking-key query");
                    bytes = writer.toByteArray();
                    patched++;
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(0); // Reproducible output, including all upstream resources/licenses.
                output.putNextEntry(copy);
                output.write(bytes);
                output.closeEntry();
            }
        } catch (Exception error) {
            Files.deleteIfExists(temporary);
            throw error;
        }
        if (patched != 1) {
            Files.deleteIfExists(temporary);
            throw new IllegalStateException("Compose mouse bridge is absent");
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Patched one Compose macOS locking-key query: " + target);
    }
}
