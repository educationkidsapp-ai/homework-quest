package quest.api.samples

import quest.api.dto.Bilingual
import quest.api.dto.Ingredient
import quest.api.dto.Option
import quest.api.dto.Tile

internal fun ing(emoji: String, name: String) = Ingredient(emoji, name)
internal fun tip(en: String, ar: String) = Bilingual(en, ar)
internal fun t(id: String, label: String? = null, pic: String? = null) = Tile(id, label = label, illustrationKey = pic)
internal fun opts(vararg labels: String): List<Option> = labels.mapIndexed { i, l -> Option("abcd"[i].toString(), l) }
internal fun idOf(options: List<Option>, label: String) = options.first { it.label == label }.id
