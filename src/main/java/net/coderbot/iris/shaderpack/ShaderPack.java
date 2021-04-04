package net.coderbot.iris.shaderpack;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import net.coderbot.iris.Iris;
import net.coderbot.iris.gl.texture.InternalTextureFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.InvalidIdentifierException;
import net.minecraft.util.registry.BuiltinRegistries;
import net.minecraft.util.registry.DynamicRegistryManager;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.Nullable;

public class ShaderPack {
	private final ProgramSet base;
	@Nullable
	private final ProgramSet overworld;
	private final ProgramSet nether;
	private final ProgramSet end;

	private IdMap idMap;
	private Map<String, Map<String, String>> langMap;
	private ShaderProperties shaderProperties;
	private ProgramSource compositeFinal;
	private PackDirectives packDirectives;

	public ShaderPack(Path root) throws IOException {
		ShaderProperties shaderProperties = loadProperties(root, "shaders.properties")
			.map(ShaderProperties::new)
			.orElseGet(ShaderProperties::empty);

		this.base = new ProgramSet(root, root, shaderProperties, this);
		this.overworld = loadOverrides(root, "world0", shaderProperties, this);
		this.nether = loadOverrides(root, "world-1", shaderProperties, this);
		this.end = loadOverrides(root, "world1", shaderProperties, this);

		this.idMap = new IdMap(root);
		this.langMap = parseLangEntries(root);
	}

	@Nullable
	private ProgramSet loadOverrides(Path root, String subfolder, ShaderProperties shaderProperties, ShaderPack pack) throws IOException {
		Path sub = root.resolve(subfolder);

		if (Files.exists(sub)) {
			return new ProgramSet(sub, root, shaderProperties, pack);
		}

//		this.compositeFinal = readProgramSource(root, "final", this, this.base, shaderProperties);
//
//		this.idMap = new IdMap(root);
//		this.langMap = parseLangEntries(root);
//
//		this.shaderProperties = shaderProperties;

		return null;
	}

	// TODO: Copy-paste from IdMap, find a way to deduplicate this
	private static Optional<Properties> loadProperties(Path shaderPath, String name) {
		Properties properties = new Properties();

		try {
			properties.load(Files.newInputStream(shaderPath.resolve(name)));
		} catch (IOException e) {
			Iris.logger.debug("An " + name + " file was not found in the current shaderpack");

			return Optional.empty();
		}

		return Optional.of(properties);
	}

	public ProgramSet getProgramSet(DimensionId dimension) {
		ProgramSet overrides;

		switch (dimension) {
			case OVERWORLD:
				overrides = overworld;
				break;
			case NETHER:
				overrides = nether;
				break;
			case END:
				overrides = end;
				break;
			default:
				throw new IllegalArgumentException("Unknown dimension " + dimension);
		}

		return ProgramSet.merged(base, overrides);
	}

	public IdMap getIdMap() {
		return idMap;
	}

	public Map<String, Map<String, String>> getLangMap() {
		return langMap;
	}

	public PackDirectives getPackDirectives() {
		return packDirectives;
	}

	public Properties getShaderProperties() {
		return this.shaderProperties.asProperties();
	}

	private static ProgramSource readProgramSource(Path root, Path inclusionRoot, String program, ProgramSet programSet, ShaderProperties properties) throws IOException {
		String vertexSource = null;
		String geometrySource = null;
		String fragmentSource = null;

		try {
			Path vertexPath = root.resolve(program + ".vsh");
			vertexSource = readFile(vertexPath);

			if (vertexSource != null) {
				vertexSource = ShaderPreprocessor.process(root, vertexPath, vertexSource);
			}
		} catch (IOException e) {
			// TODO: Better handling?
			throw e;
		}

		try {
			Path geometryPath = root.resolve(program + ".gsh");
			geometrySource = readFile(geometryPath);

			if (geometrySource != null) {
				geometrySource = ShaderPreprocessor.process(inclusionRoot, geometryPath, geometrySource);
			}
		} catch (IOException e) {
			// TODO: Better handling?
			throw e;
		}

		try {
			Path fragmentPath = root.resolve(program + ".fsh");
			fragmentSource = readFile(fragmentPath);

			if (fragmentSource != null) {
				fragmentSource = ShaderPreprocessor.process(root, fragmentPath, fragmentSource);
			}
		} catch (IOException e) {
			// TODO: Better handling?
			throw e;
		}

		return new ProgramSource(program, vertexSource, geometrySource, fragmentSource, programSet, properties);
	}

	private static String readFile(Path path) throws IOException {
		try {
			return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
		} catch (FileNotFoundException | NoSuchFileException e) {
			return null;
		}
	}

	private Map<String, Map<String, String>> parseLangEntries(Path root) throws IOException {
		Path langFolderPath = root.resolve("lang");
		Map<String, Map<String, String>> allLanguagesMap = new HashMap<>();

		if (!Files.exists(langFolderPath)) {
			return allLanguagesMap;
		}
		//We are using a max depth of one to ensure we only get the surface level *files* without going deeper
		// we also want to avoid any directories while filtering
		//Basically, we want the immediate files nested in the path for the langFolder
		//There is also Files.list which can be used for similar behavior
		Files.walk(langFolderPath, 1).filter(path -> !Files.isDirectory(path)).forEach(path -> {

			Map<String, String> currentLanguageMap = new HashMap<>();
			//some shaderpacks use optifines file name coding which is different than minecraft's.
			//An example of this is using "en_US.lang" compared to "en_us.json"
			//also note that optifine uses a property scheme for loading language entries to keep parity with other optifine features
			String currentFileName = path.getFileName().toString().toLowerCase();
			String currentLangCode = currentFileName.substring(0, currentFileName.lastIndexOf("."));
			Properties properties = new Properties();

			try {
				properties.load(Files.newInputStream(path));
			} catch (IOException e) {
				Iris.logger.error("Error while parsing languages for shaderpacks! Expected File Path: {}", path);
				Iris.logger.catching(Level.ERROR, e);
			}

			properties.forEach((key, value) -> currentLanguageMap.put(key.toString(), value.toString()));
			allLanguagesMap.put(currentLangCode, currentLanguageMap);
		});

		return allLanguagesMap;
	}
}
