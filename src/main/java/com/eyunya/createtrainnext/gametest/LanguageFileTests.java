package com.eyunya.createtrainnext.gametest;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.eyunya.createtrainnext.CreateTrainNext;
import com.google.gson.stream.JsonReader;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Checks the two language files against each other and against themselves.
 *
 * <p>Minecraft reads a language file into a map, so a key written twice keeps only the second of its
 * texts and nothing says so. The ghost's note that a curve gets an anchor between two arcs and the
 * refusal for starting at a junction once shared a key that way, and the note came out as "Cannot start
 * from a junction." A key missing from one language shows its raw name there instead of a message.
 *
 * <p>Run with {@code ./gradlew runGameTestServer}.
 */
@GameTestHolder(CreateTrainNext.ID)
@PrefixGameTestTemplate(false)
public class LanguageFileTests {

	private static final String EMPTY = "empty";

	@GameTest(template = EMPTY)
	public static void everyKeyIsWrittenOnceAndInBothLanguages(GameTestHelper helper) {
		List<String> english = keysIn("en_us.json");
		List<String> japanese = keysIn("ja_jp.json");

		helper.assertTrue(twice(english).isEmpty(),
			"en_us.json gives these keys more than once, so only the last text of each is used: " + twice(english));
		helper.assertTrue(twice(japanese).isEmpty(),
			"ja_jp.json gives these keys more than once, so only the last text of each is used: " + twice(japanese));

		Set<String> onlyEnglish = new LinkedHashSet<>(english);
		onlyEnglish.removeAll(japanese);
		Set<String> onlyJapanese = new LinkedHashSet<>(japanese);
		onlyJapanese.removeAll(english);
		helper.assertTrue(onlyEnglish.isEmpty() && onlyJapanese.isEmpty(), "the two files should have the same keys, but only "
			+ "en_us.json has " + onlyEnglish + " and only ja_jp.json has " + onlyJapanese);
		helper.succeed();
	}

	/** The keys of one of this mod's language files, in the order they are written, repeats and all. */
	private static List<String> keysIn(String file) {
		Path path = ModList.get()
			.getModFileById(CreateTrainNext.ID)
			.getFile()
			.findResource("assets", CreateTrainNext.ID, "lang", file);
		List<String> keys = new ArrayList<>();
		try (Reader text = Files.newBufferedReader(path, StandardCharsets.UTF_8); JsonReader reader = new JsonReader(text)) {
			reader.beginObject();
			while (reader.hasNext()) {
				keys.add(reader.nextName());
				reader.skipValue();
			}
			reader.endObject();
		} catch (IOException e) {
			throw new IllegalStateException("cannot read " + file, e);
		}
		return keys;
	}

	private static Set<String> twice(List<String> keys) {
		Set<String> seen = new HashSet<>();
		Set<String> repeated = new LinkedHashSet<>();
		for (String key : keys)
			if (!seen.add(key))
				repeated.add(key);
		return repeated;
	}
}
