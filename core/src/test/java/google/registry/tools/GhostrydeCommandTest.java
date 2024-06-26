// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.keyring.api.Keyring;
import google.registry.rde.Ghostryde;
import google.registry.testing.BouncyCastleProviderExtension;
import google.registry.testing.FakeKeyringModule;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link GhostrydeCommand}. */
class GhostrydeCommandTest extends CommandTestCase<GhostrydeCommand> {

  private static final byte[] SONG_BY_CHRISTINA_ROSSETTI =
      """
      When I am dead, my dearest,      \s
        Sing no sad songs for me;      \s
      Plant thou no roses at my head,  \s
        Nor shady cypress tree:        \s
      Be the green grass above me      \s
        With showers and dewdrops wet; \s
      And if thou wilt, remember,      \s
        And if thou wilt, forget.      \s
                                       \s
      I shall not see the shadows,     \s
        I shall not feel the rain;     \s
      I shall not hear the nightingale \s
        Sing on, as if in pain:        \s
      And dreaming through the twilight\s
        That doth not rise nor set,    \s
      Haply I may remember,            \s
        And haply may forget.          \s
      """
          .getBytes(UTF_8);

  @RegisterExtension
  final BouncyCastleProviderExtension bouncy = new BouncyCastleProviderExtension();

  private Keyring keyring;

  @BeforeEach
  void beforeEach() {
    keyring = new FakeKeyringModule().get();
    command.rdeStagingDecryptionKey = keyring::getRdeStagingDecryptionKey;
    command.rdeStagingEncryptionKey = keyring::getRdeStagingEncryptionKey;
  }

  @Test
  void testParameters_cantSpecifyBothEncryptAndDecrypt() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommand("--encrypt", "--decrypt"));
    assertThat(thrown).hasMessageThat().isEqualTo("Please specify either --encrypt or --decrypt");
  }

  @Test
  void testParameters_mustSpecifyOneOfEncryptOrDecrypt() throws Exception {
    Path inputFile = Files.createFile(tmpDir.resolve("foo.dat"));
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommand("--input=" + inputFile, "--output=bar.dat"));
    assertThat(thrown).hasMessageThat().isEqualTo("Please specify either --encrypt or --decrypt");
  }

  @Test
  void testEncrypt_outputPathIsRequired() throws Exception {
    Path inputFile = Files.createFile(tmpDir.resolve("foo.dat"));
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> runCommand("--encrypt", "--input=" + inputFile));
    assertThat(thrown).hasMessageThat().isEqualTo("--output path is required in --encrypt mode");
  }

  @Test
  void testEncrypt_outputIsAFile_writesToFile() throws Exception {
    Path inFile = tmpDir.resolve("atrain.txt");
    Path outFile = tmpDir.resolve("out.dat");
    Files.write(inFile, SONG_BY_CHRISTINA_ROSSETTI);
    runCommand("--encrypt", "--input=" + inFile, "--output=" + outFile);
    byte[] decoded =
        Ghostryde.decode(Files.readAllBytes(outFile), keyring.getRdeStagingDecryptionKey());
    assertThat(decoded).isEqualTo(SONG_BY_CHRISTINA_ROSSETTI);
  }

  @Test
  void testEncrypt_outputIsADirectory_appendsGhostrydeExtension() throws Exception {
    Path inFile = tmpDir.resolve("atrain.txt");
    Files.write(inFile, SONG_BY_CHRISTINA_ROSSETTI);
    runCommand("--encrypt", "--input=" + inFile, "--output=" + tmpDir.toString());
    Path lenOutFile = tmpDir.resolve("atrain.txt.length");
    assertThat(Ghostryde.readLength(Files.newInputStream(lenOutFile)))
        .isEqualTo(SONG_BY_CHRISTINA_ROSSETTI.length);
    Path outFile = tmpDir.resolve("atrain.txt.ghostryde");
    byte[] decoded =
        Ghostryde.decode(Files.readAllBytes(outFile), keyring.getRdeStagingDecryptionKey());
    assertThat(decoded).isEqualTo(SONG_BY_CHRISTINA_ROSSETTI);
  }

  @Test
  void testDecrypt_outputIsAFile_writesToFile() throws Exception {
    Path inFile = tmpDir.resolve("atrain.txt");
    Path outFile = tmpDir.resolve("out.dat");
    Files.write(
        inFile, Ghostryde.encode(SONG_BY_CHRISTINA_ROSSETTI, keyring.getRdeStagingEncryptionKey()));
    runCommand("--decrypt", "--input=" + inFile, "--output=" + outFile);
    assertThat(Files.readAllBytes(outFile)).isEqualTo(SONG_BY_CHRISTINA_ROSSETTI);
  }

  @Test
  void testDecrypt_outputIsADirectory_AppendsDecryptExtension() throws Exception {
    Path inFile = tmpDir.resolve("atrain.ghostryde");
    Files.write(
        inFile, Ghostryde.encode(SONG_BY_CHRISTINA_ROSSETTI, keyring.getRdeStagingEncryptionKey()));
    runCommand("--decrypt", "--input=" + inFile, "--output=" + tmpDir.toString());
    Path outFile = tmpDir.resolve("atrain.ghostryde.decrypt");
    assertThat(Files.readAllBytes(outFile)).isEqualTo(SONG_BY_CHRISTINA_ROSSETTI);
  }

  @Test
  void testDecrypt_outputIsStdOut() throws Exception {
    Path inFile = tmpDir.resolve("atrain.ghostryde");
    Files.write(
        inFile, Ghostryde.encode(SONG_BY_CHRISTINA_ROSSETTI, keyring.getRdeStagingEncryptionKey()));
    runCommand("--decrypt", "--input=" + inFile);
    assertThat(getStdoutAsString().getBytes(UTF_8)).isEqualTo(SONG_BY_CHRISTINA_ROSSETTI);
  }
}
