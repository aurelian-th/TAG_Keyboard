package org.futo.voiceinput.shared.rambler

import android.view.inputmethod.InputConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.regex.Pattern

/**
 * Callback interface for LLM-based text rewriting.
 * Implementations should call the actual Qwen model for inference.
 */
interface QwenRewriter {
    /**
     * Rewrite text using the Qwen LLM.
     * @param prompt The full ChatML-formatted prompt
     * @return The cleaned/rewritten text from the model
     */
    suspend fun rewrite(prompt: String): String

    /**
     * Check if the Qwen model is loaded and ready.
     */
    fun isReady(): Boolean
}

interface LlamaEngineRunner {
    suspend fun rewrite(
        rawText: String,
        stripDisfluencies: Boolean = true,
        restoreDiacritics: Boolean = true,
        smartPunctuation: Boolean = true,
        preserveLoanwords: Boolean = true,
        aiMode: Boolean = false,
        textContext: String? = null
    ): String
}

class RamblerEngine(
    val modelFile: File? = null,
    var qwenRewriter: QwenRewriter? = null
) : LlamaEngineRunner {

    companion object {
        const val SYSTEM_PROMPT = """<|im_start|>system
You are a real-time multilingual voice typing assistant. Clean the transcribed speech: remove stutters and filler words (um, uh, ăă, etc.), resolve self-corrections (e.g. "no wait actually X" -> keep only X), insert requested emojis, and apply edits. Support mixed Romanian, English, Russian, and French without translation. Keep original meaning and language. Output ONLY the cleaned result, no explanations.<|im_end|>"""

        fun buildPrompt(rawTranscription: String): String {
            return "$SYSTEM_PROMPT\n<|im_start|>user\n$rawTranscription<|im_end|>\n<|im_start|>assistant\n"
        }

        // Filler words regex across English, Romanian, Russian, French, German, Spanish
        private val FILLERS_PATTERN = Pattern.compile(
            "\\b(?:" +
            // English fillers
            "um+|uh+|er+|ah+|umm+|uhh+|errm+|you know|i mean|" +
            // Romanian fillers & hesitation
            "ăă+|îî+|ăm+|păi|adică|gen|cum ar fi|știi ce zic|mă rog|" +
            // Russian fillers
            "ээ+|мм+|ну|типа|значит|короче|как бы|" +
            // French fillers
            "euh+|euhm+|bah|genre|du coup|" +
            // German & Spanish fillers
            "ähm+|äh+|halt|quasi|este|o sea" +
            ")\\b[\\s,]*",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CHARACTER_CLASS
        )

        // Filler "like" when surrounded by comma or at start
        private val LIKE_FILLER_PATTERN = Pattern.compile(
            "(?:^|[,\\s]+)\\blike\\b[,\\s]+",
            Pattern.CASE_INSENSITIVE
        )

        // Comprehensive multilingual emoji dictionary (EN, RO, RU, FR)
        private val EMOJI_MAP = mapOf(
            // Food & Drink
            "sushi" to "🍣", "suși" to "🍣", "суши" to "🍣",
            "pizza" to "🍕", "пицца" to "🍕",
            "burger" to "🍔", "hamburger" to "🍔", "бургер" to "🍔",
            "beer" to "🍺", "bere" to "🍺", "biere" to "🍺", "bière" to "🍺", "пиво" to "🍺",
            "beers" to "🍻", "cheers" to "🍻", "beri" to "🍻", "noroc" to "🍻", "santé" to "🍻",
            "coffee" to "☕", "cafea" to "☕", "cafe" to "☕", "café" to "☕", "espresso" to "☕", "кофе" to "☕",
            "cake" to "🎂", "tort" to "🎂", "gateau" to "🎂", "gâteau" to "🎂", "торт" to "🎂",

            // Emotions & Faces
            "laughing" to "😂", "laugh" to "😂", "lol" to "😂", "rofl" to "😂", "haha" to "😂",
            "râs" to "😂", "ras" to "😂", "rire" to "😂", "смех" to "😂", "хаха" to "😂",
            "crying" to "😭", "cry" to "😭", "tears" to "😭", "plâns" to "😭", "plange" to "😭",
            "pleure" to "😭", "слёзы" to "😭", "плач" to "😭",
            "sad" to "😢", "trist" to "😢", "triste" to "😢", "грустно" to "😢", "печаль" to "😢",
            "smile" to "😊", "smiling" to "😊", "happy" to "😊", "zâmbet" to "😊", "zambet" to "😊",
            "fericit" to "😊", "sourire" to "😊", "улыбка" to "😊", "радость" to "😊",
            "wink" to "😉", "winking" to "😉", "ochiul" to "😉", "clin d'oeil" to "😉", "подмигивание" to "😉",
            "kiss" to "😘", "kissing" to "😘", "pupic" to "😘", "pupici" to "😘", "bisou" to "😘", "поцелуй" to "😘",
            "cool" to "😎", "sunglasses" to "😎", "ochelari" to "😎", "lunettes" to "😎", "круто" to "😎", "очки" to "😎",
            "skull" to "💀", "dead" to "💀", "mort" to "💀", "craniu" to "💀", "череп" to "💀",

            // Gestures & Symbols
            "heart" to "❤️", "love" to "❤️", "red heart" to "❤️", "inimă" to "❤️", "inima" to "❤️",
            "inimioară" to "❤️", "dragoste" to "❤️", "cœur" to "❤️", "coeur" to "❤️", "amour" to "❤️",
            "сердце" to "❤️", "любовь" to "❤️",
            "thumbs up" to "👍", "thumb up" to "👍", "like" to "👍", "ok" to "👍", "bravo" to "👍",
            "deget" to "👍", "pouce" to "👍", "класс" to "👍", "лайк" to "👍", "отлично" to "👍",
            "fire" to "🔥", "lit" to "🔥", "flame" to "🔥", "foc" to "🔥", "feu" to "🔥", "огонь" to "🔥", "пламя" to "🔥",
            "rocket" to "🚀", "rachetă" to "🚀", "racheta" to "🚀", "fusée" to "🚀", "fusee" to "🚀", "ракета" to "🚀",
            "party" to "🎉", "celebrate" to "🎉", "celebration" to "🎉", "petrecere" to "🎉", "fête" to "🎉", "fete" to "🎉",
            "праздник" to "🎉", "вечеринка" to "🎉",
            "star" to "⭐", "stea" to "⭐", "étoile" to "⭐", "etoile" to "⭐", "звезда" to "⭐",
            "check" to "✅", "checkmark" to "✅", "done" to "✅", "bifat" to "✅", "gata" to "✅",
            "validé" to "✅", "valide" to "✅", "галочка" to "✅", "готово" to "✅",
            "eyes" to "👀", "look" to "👀", "ochi" to "👀", "yeux" to "👀", "глаза" to "👀",
            "sun" to "☀️", "sunny" to "☀️", "soare" to "☀️", "soleil" to "☀️", "солнце" to "☀️",
            "money" to "💰", "cash" to "💰", "dollar" to "💰", "bani" to "💰", "argent" to "💰", "деньги" to "💰",
            "pray" to "🙏", "thanks" to "🙏", "thank you" to "🙏", "please" to "🙏", "mulțumesc" to "🙏",
            "multumesc" to "🙏", "merci" to "🙏", "спасибо" to "🙏",
            "clap" to "👏", "applause" to "👏", "aplauze" to "👏", "аплодисменты" to "👏",
            "100" to "💯", "hundred" to "💯", "sută" to "💯", "suta" to "💯", "сто" to "💯",
            "lightning" to "⚡", "bolt" to "⚡", "fulger" to "⚡", "éclair" to "⚡", "молния" to "⚡",
            "car" to "🚗", "mașină" to "🚗", "masina" to "🚗", "voiture" to "🚗", "машина" to "🚗", "тачка" to "🚗",
            "dog" to "🐶", "puppy" to "🐶", "câine" to "🐶", "caine" to "🐶", "cățel" to "🐶", "chien" to "🐶", "собака" to "🐶",
            "cat" to "🐱", "kitten" to "🐱", "pisică" to "🐱", "pisica" to "🐱", "chat" to "🐱", "кот" to "🐱", "кошка" to "🐱"
        )

        // Romanian diacritic restoration dictionary
        private val ROMANIAN_DIACRITICS = mapOf(
            "si" to "și", "Si" to "Și",
            "sa" to "să", "Sa" to "Să",
            "in" to "în", "In" to "În",
            "intr" to "într", "Intr" to "Într",
            "intr-un" to "într-un", "Intr-un" to "Într-un",
            "intr-o" to "într-o", "Intr-o" to "Într-o",
            "dintr-un" to "dintr-un", "Dintr-un" to "Dintr-un",
            "dintr-o" to "dintr-o", "Dintr-o" to "Dintr-o",
            "toti" to "toți", "Toti" to "Toți",
            "toate" to "toate", "Toate" to "Toate",
            "toata" to "toată", "Toata" to "Toată",
            "stiu" to "știu", "Stiu" to "Știu",
            "stii" to "știi", "Stii" to "Știi",
            "stie" to "știe", "Stie" to "Știe",
            "stim" to "știm", "Stim" to "Știm",
            "stiti" to "știți", "Stiti" to "Știți",
            "trebuie" to "trebuie", "Trebuie" to "Trebuie",
            "facem" to "facem", "Facem" to "Facem",
            "inainte" to "înainte", "Inainte" to "Înainte",
            "decat" to "decât", "Decat" to "Decât",
            "cand" to "când", "Cand" to "Când",
            "fara" to "fără", "Fara" to "Fără",
            "viata" to "viață", "Viata" to "Viață",
            "tara" to "țară", "Tara" to "Țară",
            "acum" to "acum", "Acum" to "Acum",
            "acasa" to "acasă", "Acasa" to "Acasă",
            "asteapta" to "așteaptă", "Asteapta" to "Așteaptă",
            "asteptam" to "așteptăm", "Asteptam" to "Așteptăm",
            "astept" to "aștept", "Astept" to "Aștept",
            "invat" to "învăț", "Invat" to "Învăț",
            "inceput" to "început", "Inceput" to "Început",
            "incepe" to "începe", "Incepe" to "Începe",
            "sfarsit" to "sfârșit", "Sfarsit" to "Sfârșit",
            "inca" to "încă", "Inca" to "Încă",
            "acelasi" to "același", "Acelasi" to "Același",
            "aceeasi" to "aceeași", "Aceeasi" to "Aceeași",
            "aceiasi" to "aceiași", "Aceiasi" to "Aceiași",
            "adica" to "adică", "Adica" to "Adică",
            "usor" to "ușor", "Usor" to "Ușor",
            "baiat" to "băiat", "Baiat" to "Băiat",
            "baieti" to "băieți", "Baieti" to "Băieți",
            "fata" to "fată", "Fata" to "Fată",
            "fetita" to "fetiță", "Fetita" to "Fetiță",
            "masina" to "mașină", "Masina" to "Mașină",
            "strada" to "stradă", "Strada" to "Stradă",
            "seara" to "seară", "Seara" to "Seară",
            "dimineata" to "dimineață", "Dimineata" to "Dimineață",
            "buna" to "bună", "Buna" to "Bună",
            "oras" to "oraș", "Oras" to "Oraș",
            "orasul" to "orașul", "Orasul" to "Orașul",
            "pret" to "preț", "Pret" to "Preț",
            "pretul" to "prețul", "Pretul" to "Prețul",
            "solutie" to "soluție", "Solutie" to "Soluție",
            "solutii" to "soluții", "Solutii" to "Soluții",
            "situatie" to "situație", "Situatie" to "Situație",
            "situatii" to "situații", "Situatii" to "Situații",
            "informatii" to "informații", "Informatii" to "Informații",
            "atentie" to "atenție", "Atentie" to "Atenție",
            "multumesc" to "mulțumesc", "Multumesc" to "Mulțumesc",
            "multumim" to "mulțumim", "Multumim" to "Mulțumim",
            "placere" to "plăcere", "Placere" to "Plăcere",
            "placut" to "plăcut", "Placut" to "Plăcut",
            "impreuna" to "împreună", "Impreuna" to "Împreună",
            "urma" to "urmă", "Urma" to "Urmă",
            "romania" to "România", "Romania" to "România",
            "romana" to "română", "Romana" to "Română"
        )

        // Code-switching developer / tech loanwords to preserve in proper Latin casing
        private val TECH_LOANWORDS = mapOf(
            "pr" to "PR", "pi ar" to "PR", "piar" to "PR",
            "merge" to "merge", "mergi" to "merge",
            "release" to "release", "reliz" to "release",
            "commit" to "commit", "comit" to "commit",
            "repo" to "repo",
            "deploy" to "deploy", "deploi" to "deploy",
            "bug" to "bug", "bag" to "bug",
            "fix" to "fix", "fics" to "fix",
            "frontend" to "frontend",
            "backend" to "backend",
            "pull request" to "pull request",
            "push" to "push", "pusi" to "push",
            "feature" to "feature", "ficiur" to "feature",
            "branch" to "branch", "brenci" to "branch"
        )

        // Words that indicate an interrogative sentence
        private val QUESTION_WORDS = setOf(
            // English
            "what", "where", "when", "why", "how", "who", "which", "whose", "whom", "can", "could", "would", "should", "is", "are", "do", "does", "did",
            // Romanian
            "ce", "cine", "unde", "când", "cand", "cum", "de ce", "oare", "care", "câți", "câte",
            // Russian
            "что", "где", "когда", "почему", "зачем", "как", "кто", "куда", "откуда", "чей",
            // French
            "quoi", "où", "quand", "pourquoi", "comment", "qui"
        )

        /**
         * Detects conversational edit commands over existing text.
         * Example: "Change red to green" -> Pair("red", "green")
         * Example: "From 10 to 12" -> Pair("10", "12")
         * Example: "Schimbă roșu în verde" -> Pair("roșu", "verde")
         * Example: "De la 10 la 12" -> Pair("10", "12")
         */
        fun parseEditCommand(input: String): Pair<String, String>? {
            val clean = input.trim().trimEnd('.', '!', '?', ';')

            // 1. English patterns ("change red to green", "from red to green", "change 10 to 12", "from 10 to 12")
            val enChange = Pattern.compile(
                "(?i)^(?:please\\s+)?(?:change|replace|switch|turn|make)\\s+(?:from\\s+)?([a-zA-Z0-9ăâîșțĂÂÎȘȚа-яА-ЯёЁàâçéèêëîïôûùüÿñæœ\\- ]+?)\\s+(?:to|into|with)\\s+([a-zA-Z0-9ăâîșțĂÂÎȘȚа-яА-ЯёЁàâçéèêëîïôûùüÿñæœ\\- ]+)$",
                Pattern.UNICODE_CHARACTER_CLASS
            )
            var m = enChange.matcher(clean)
            if (m.matches()) {
                val t = m.group(1)?.trim() ?: ""
                val r = m.group(2)?.trim() ?: ""
                if (t.isNotEmpty() && r.isNotEmpty()) return Pair(t, r)
            }

            val enFromTo = Pattern.compile(
                "(?i)^from\\s+([a-zA-Z0-9ăâîșțĂÂÎȘȚа-яА-ЯёЁàâçéèêëîïôûùüÿñæœ\\- ]+?)\\s+(?:to|into)\\s+([a-zA-Z0-9ăâîșțĂÂÎȘȚа-яА-ЯёЁàâçéèêëîïôûùüÿñæœ\\- ]+)$",
                Pattern.UNICODE_CHARACTER_CLASS
            )
            m = enFromTo.matcher(clean)
            if (m.matches()) {
                val t = m.group(1)?.trim() ?: ""
                val r = m.group(2)?.trim() ?: ""
                if (t.isNotEmpty() && r.isNotEmpty()) return Pair(t, r)
            }

            // 2. Romanian patterns ("schimbă roșu în verde", "de la 10 la 12", "înlocuiește 10 cu 12")
            val roChange = Pattern.compile(
                "(?i)^(?:te\\s+rog\\s+)?(?:schimbă|schimba|înlocuiește|inlocuieste|pune|treci|modifică|modifica)\\s+(?:de\\s+la\\s+)?([a-zA-Z0-9ăâîșțĂÂÎȘȚ\\- ]+?)\\s+(?:în|in|cu|la)\\s+([a-zA-Z0-9ăâîșțĂÂÎȘȚ\\- ]+)$",
                Pattern.UNICODE_CHARACTER_CLASS
            )
            m = roChange.matcher(clean)
            if (m.matches()) {
                val t = m.group(1)?.trim() ?: ""
                val r = m.group(2)?.trim() ?: ""
                if (t.isNotEmpty() && r.isNotEmpty()) return Pair(t, r)
            }

            val roFromTo = Pattern.compile(
                "(?i)^de\\s+la\\s+([a-zA-Z0-9ăâîșțĂÂÎȘȚ\\- ]+?)\\s+(?:la|în|in|cu)\\s+([a-zA-Z0-9ăâîșțĂÂÎȘȚ\\- ]+)$",
                Pattern.UNICODE_CHARACTER_CLASS
            )
            m = roFromTo.matcher(clean)
            if (m.matches()) {
                val t = m.group(1)?.trim() ?: ""
                val r = m.group(2)?.trim() ?: ""
                if (t.isNotEmpty() && r.isNotEmpty()) return Pair(t, r)
            }

            // 3. Russian patterns ("поменяй красное на зеленое", "с 10 на 12")
            val ruChange = Pattern.compile(
                "(?i)^(?:пожалуйста\\s+)?(?:поменяй|замени|измени)\\s+(?:с\\s+)?([а-яА-ЯёЁ0-9a-zA-Z\\- ]+?)\\s+(?:на|в)\\s+([а-яА-ЯёЁ0-9a-zA-Z\\- ]+)$",
                Pattern.UNICODE_CHARACTER_CLASS
            )
            m = ruChange.matcher(clean)
            if (m.matches()) {
                val t = m.group(1)?.trim() ?: ""
                val r = m.group(2)?.trim() ?: ""
                if (t.isNotEmpty() && r.isNotEmpty()) return Pair(t, r)
            }

            val ruFromTo = Pattern.compile(
                "(?i)^с\\s+([а-яА-ЯёЁ0-9a-zA-Z\\- ]+?)\\s+на\\s+([а-яА-ЯёЁ0-9a-zA-Z\\- ]+)$",
                Pattern.UNICODE_CHARACTER_CLASS
            )
            m = ruFromTo.matcher(clean)
            if (m.matches()) {
                val t = m.group(1)?.trim() ?: ""
                val r = m.group(2)?.trim() ?: ""
                if (t.isNotEmpty() && r.isNotEmpty()) return Pair(t, r)
            }

            // 4. French patterns ("change rouge en vert", "de 10 à 12")
            val frChange = Pattern.compile(
                "(?i)^(?:s'il\\s+te\\s+plaît\\s+)?(?:change|remplace)\\s+(?:de\\s+)?([a-zA-Zàâçéèêëîïôûùüÿñæœ0-9\\- ]+?)\\s+(?:en|par|à)\\s+([a-zA-Zàâçéèêëîïôûùüÿñæœ0-9\\- ]+)$",
                Pattern.UNICODE_CHARACTER_CLASS
            )
            m = frChange.matcher(clean)
            if (m.matches()) {
                val t = m.group(1)?.trim() ?: ""
                val r = m.group(2)?.trim() ?: ""
                if (t.isNotEmpty() && r.isNotEmpty()) return Pair(t, r)
            }

            val frFromTo = Pattern.compile(
                "(?i)^de\\s+([a-zA-Zàâçéèêëîïôûùüÿñæœ0-9\\- ]+?)\\s+(?:à|en|par)\\s+([a-zA-Zàâçéèêëîïôûùüÿñæœ0-9\\- ]+)$",
                Pattern.UNICODE_CHARACTER_CLASS
            )
            m = frFromTo.matcher(clean)
            if (m.matches()) {
                val t = m.group(1)?.trim() ?: ""
                val r = m.group(2)?.trim() ?: ""
                if (t.isNotEmpty() && r.isNotEmpty()) return Pair(t, r)
            }

            return null
        }

        /**
         * Detects and resolves self-corrections / false starts in speech.
         * Example: "no no no actually do that" -> "do that"
         * Example: "șase mere, nu, stai, 10 mere" -> "10 mere"
         * Example: "nu nu nu de fapt fă aia" -> "fă aia"
         * Example: "I want five, no wait, ten apples" -> "ten apples"
         * Example: "10 no wait 12" -> "12"
         */
        /**
         * Detects and resolves self-corrections / false starts in speech.
         * Example: "Testing 1, 2, 3, 4, 5, 6, ok so we have 10 tests, no actually we have 12 tests."
         *       -> "Testing 1, 2, 3, 4, 5, 6, ok so we have 12 tests."
         * Example: "no no no actually do that" -> "do that"
         * Example: "șase mere, nu, stai, 10 mere" -> "10 mere"
         * Example: "10 no wait 12" -> "12"
         */
        fun handleSelfCorrections(input: String): String {
            var text = input.trim()

            // 1. Detect multi-negative starts: "no no no actually...", "nu nu nu de fapt..."
            val leadingRepeatedNegative = Pattern.compile(
                "(?i)^(?:no[\\s,]+)+actually[\\s,]+(.+)$",
                Pattern.UNICODE_CHARACTER_CLASS
            )
            var mLead = leadingRepeatedNegative.matcher(text)
            if (mLead.matches()) {
                val clean = mLead.group(1)?.trim()
                if (!clean.isNullOrEmpty()) return clean
            }

            val leadingRoNegative = Pattern.compile(
                "(?i)^(?:nu[\\s,]+)+(?:de\\s*fapt|defapt|stai)[\\s,]+(.+)$",
                Pattern.UNICODE_CHARACTER_CLASS
            )
            mLead = leadingRoNegative.matcher(text)
            if (mLead.matches()) {
                val clean = mLead.group(1)?.trim()
                if (!clean.isNullOrEmpty()) return clean
            }

            val leadingRuNegative = Pattern.compile(
                "(?i)^(?:нет[\\s,]+)+(?:на\\s*самом\\s*деле|подожди|погоди)[\\s,]+(.+)$",
                Pattern.UNICODE_CHARACTER_CLASS
            )
            mLead = leadingRuNegative.matcher(text)
            if (mLead.matches()) {
                val clean = mLead.group(1)?.trim()
                if (!clean.isNullOrEmpty()) return clean
            }

            val leadingFrNegative = Pattern.compile(
                "(?i)^(?:non[\\s,]+)+(?:en\\s*fait|attends|plutôt)[\\s,]+(.+)$",
                Pattern.UNICODE_CHARACTER_CLASS
            )
            mLead = leadingFrNegative.matcher(text)
            if (mLead.matches()) {
                val clean = mLead.group(1)?.trim()
                if (!clean.isNullOrEmpty()) return clean
            }

            // 2. Locate correction triggers anywhere in sentence
            val triggerPattern = Pattern.compile(
                "(?i)[,\\s]+(?:" +
                // English triggers: (no+)+ actually, no wait, wait no, actually, sorry, make that, scratch that, i mean
                "(?:no[,\\s]+)*(?:no[,\\s]+actually|actually|no[,\\s]+wait|wait[,\\s]+no|scratch\\s+that|make\\s+that|i\\s+mean|sorry)|" +
                // Romanian triggers: (nu+)+ de fapt, (nu+)+ stai, stai așa, defapt, de fapt, pardon, scuze, adică
                "(?:nu[,\\s]+)*(?:nu[,\\s]+de\\s*fapt|de\\s*fapt|defapt|nu[,\\s]+stai|stai[,\\s]+nu|stai\\s+așa|stai|pardon|scuze|adică)|" +
                // Russian triggers: (нет+)+ на самом деле, подожди, погоди, точнее, вернее
                "(?:нет[,\\s]+)*(?:нет[,\\s]+на\\s*самом\\s*деле|на\\s*самом\\s*деле|нет[,\\s]+подожди|подожди|погоди|точнее|вернее|в\\s*смысле)|" +
                // French triggers: (non+)+ en fait, attends, en fait, plutôt
                "(?:non[,\\s]+)*(?:non[,\\s]+en\\s*fait|en\\s*fait|non[,\\s]+attends|attends|plutôt|pardon)" +
                ")[,\\s]+",
                Pattern.UNICODE_CHARACTER_CLASS
            )

            val matcher = triggerPattern.matcher(text)
            if (!matcher.find()) {
                return text
            }

            val triggerStart = matcher.start()
            val triggerEnd = matcher.end()

            val left = text.substring(0, triggerStart).trim()
            val right = text.substring(triggerEnd).trim()

            if (right.isEmpty()) return left
            if (left.isEmpty() || left.matches("(?i)^(?:no|nu|нет|non|wait|stai)[\\s,]*$".toRegex())) {
                return right
            }

            val rightWords = right.trimEnd('.', '!', '?', ';').split("\\s+".toRegex())
            val leftWords = left.split("\\s+".toRegex())

            // Case A: Look for matching common phrase from right inside left (e.g. "we have")
            var bestMatchLeftIndex = -1
            for (len in minOf(5, rightWords.size) downTo 1) {
                val sub = rightWords.take(len).joinToString(" ").lowercase()
                val idx = left.lowercase().lastIndexOf(sub)
                if (idx >= 0) {
                    bestMatchLeftIndex = idx
                    break
                }
            }

            if (bestMatchLeftIndex >= 0) {
                val prefix = left.substring(0, bestMatchLeftIndex).trimEnd()
                return if (prefix.isEmpty()) right else "$prefix $right"
            }

            // Case B: Matching suffix object (e.g. "șase mere" -> "10 mere")
            val lastWordRight = rightWords.lastOrNull()?.lowercase()
            val lastWordLeft = leftWords.lastOrNull()?.lowercase()
            if (lastWordRight != null && lastWordRight == lastWordLeft) {
                val lastComma = left.lastIndexOf(',')
                val prefix = if (lastComma >= 0) left.substring(0, lastComma).trim() else ""
                return if (prefix.isEmpty()) right else "$prefix, $right"
            }

            // Case C: Spoken number replacement (e.g. "... 10", right: "12")
            val isRightNumber = right.matches("^\\d+[.!?]?$".toRegex())
            if (isRightNumber) {
                val numMatch = Regex("\\b\\d+\\b").findAll(left).lastOrNull()
                if (numMatch != null) {
                    val prefix = left.substring(0, numMatch.range.first)
                    val suffix = left.substring(numMatch.range.last + 1)
                    val cleanRight = right.trimEnd('.', '!', '?')
                    return "$prefix$cleanRight$suffix"
                }
            }

            // Case D: General clause replacement after last comma or period
            val lastClauseStart = maxOf(left.lastIndexOf(','), left.lastIndexOf('.'))
            if (lastClauseStart >= 0) {
                val prefix = left.substring(0, lastClauseStart).trim()
                val sep = left[lastClauseStart]
                return "$prefix$sep $right"
            }

            return right
        }

        /**
         * Fast regex emoji parser with full dictionary mapping.
         * Example: "Add a sushi emoji" -> "🍣"
         * Example: "sushi emoji" -> "🍣"
         * Example: "Let's grab a beer emoji tonight" -> "Let's grab a 🍺 tonight"
         * Example: "Pune emoji inimă" -> "❤️"
         */
        fun handleEmojiCommands(input: String): String {
            var text = input

            // 1. Keyword emoji regex: "[prefix] [keyword] emoji" or "[prefix] emoji [keyword]"
            val keywordPattern = Pattern.compile(
                "(?i)\\b(?:add|insert|put|pune|adaugă|добавь|mets)?\\s*(?:an?|the|un|o|niște|des)?\\s*([a-zA-ZăâîșțĂÂÎȘȚа-яА-ЯёЁàâçéèêëîïôûùüÿñæœ\\-]+)\\s+emoji\\b",
                Pattern.UNICODE_CHARACTER_CLASS
            )
            var m = keywordPattern.matcher(text)
            while (m.find()) {
                val keyword = m.group(1)?.trim()?.lowercase() ?: ""
                val emoji = EMOJI_MAP[keyword] ?: EMOJI_MAP.entries.firstOrNull { keyword.contains(it.key) }?.value
                if (emoji != null) {
                    text = text.substring(0, m.start()) + emoji + text.substring(m.end())
                    m = keywordPattern.matcher(text)
                } else {
                    break
                }
            }

            val prefixPattern = Pattern.compile(
                "(?i)\\b(?:add|insert|put|pune|adaugă|добавь|mets)?\\s*(?:an?|the|un|o|niște|des)?\\s*emoji\\s+(?:of|cu|de|с|d')?\\s*([a-zA-ZăâîșțĂÂÎȘȚа-яА-ЯёЁàâçéèêëîïôûùüÿñæœ\\-]+)\\b",
                Pattern.UNICODE_CHARACTER_CLASS
            )
            var mp = prefixPattern.matcher(text)
            while (mp.find()) {
                val keyword = mp.group(1)?.trim()?.lowercase() ?: ""
                val emoji = EMOJI_MAP[keyword] ?: EMOJI_MAP.entries.firstOrNull { keyword.contains(it.key) }?.value
                if (emoji != null) {
                    text = text.substring(0, mp.start()) + emoji + text.substring(mp.end())
                    mp = prefixPattern.matcher(text)
                } else {
                    break
                }
            }

            // 2. Generic / contextual emoji commands: "add some emoji", "pune niște emoji"
            val genericPattern = Pattern.compile(
                "(?i)\\b(?:add|insert|put|pune|adaugă|добавь|mets)\\s+(?:some\\s+emojis?|an?\\s+emoji|emojis?|niște\\s+emoji(?:-uri)?|des\\s+emojis?)\\b[.]?",
                Pattern.UNICODE_CHARACTER_CLASS
            )
            val mg = genericPattern.matcher(text)
            if (mg.find()) {
                val before = text.substring(0, mg.start()).trim()
                val lower = before.lowercase()
                val matchedEmoji = when {
                    lower.contains("party") || lower.contains("celebrat") || lower.contains("sărbător") || lower.contains("fête") -> "🎉"
                    lower.contains("congrat") || lower.contains("felicit") || lower.contains("bravo") -> "🥳"
                    lower.contains("love") || lower.contains("iubesc") || lower.contains("drag") || lower.contains("cœur") -> "❤️"
                    lower.contains("fire") || lower.contains("super") || lower.contains("awesome") || lower.contains("foc") -> "🔥"
                    lower.contains("thanks") || lower.contains("mulțum") || lower.contains("merci") || lower.contains("спасибо") -> "🙏"
                    lower.contains("beer") || lower.contains("bere") || lower.contains("biere") || lower.contains("пиво") -> "🍻"
                    lower.contains("coffee") || lower.contains("cafea") || lower.contains("café") || lower.contains("кофе") -> "☕"
                    lower.contains("pizza") || lower.contains("food") || lower.contains("mâncare") -> "🍕"
                    lower.contains("urgent") || lower.contains("rapid") || lower.contains("fast") -> "⚡"
                    lower.contains("good") || lower.contains("great") || lower.contains("bine") || lower.contains("ok") -> "👍"
                    else -> "✨"
                }
                text = if (before.isEmpty()) matchedEmoji else "$before $matchedEmoji"
            }

            return text.replace("\\s+".toRegex(), " ").trim()
        }

        /**
         * Conversational tone and style adjustments (Professional, Concise, Friendly).
         */
        fun handleRewriteCommands(input: String, textContext: String?): String {
            var text = input

            val profPattern = Pattern.compile(
                "(?i)(?:^|[,\\s]+)(?:make\\s+this\\s+sound\\s+more\\s+professional|make\\s+this\\s+professional|make\\s+it\\s+professional|fă\\s+asta\\s+să\\s+sune\\s+mai\\s+profesionist|fă-o\\s+profesională)[.]?$",
                Pattern.UNICODE_CHARACTER_CLASS
            )
            val mp = profPattern.matcher(text)
            if (mp.find()) {
                var content = text.substring(0, mp.start()).trim()
                if (content.isEmpty() && !textContext.isNullOrBlank()) {
                    content = textContext.trim()
                }
                if (content.isNotEmpty()) {
                    return rewriteProfessional(content)
                }
            }

            val shortPattern = Pattern.compile(
                "(?i)(?:^|[,\\s]+)(?:make\\s+this\\s+shorter\\s+and\\s+more\\s+direct|make\\s+this\\s+shorter|make\\s+this\\s+concise|make\\s+it\\s+concise|fă\\s+asta\\s+mai\\s+scurtă|scurtează\\s+asta)[.]?$",
                Pattern.UNICODE_CHARACTER_CLASS
            )
            val ms = shortPattern.matcher(text)
            if (ms.find()) {
                var content = text.substring(0, ms.start()).trim()
                if (content.isEmpty() && !textContext.isNullOrBlank()) {
                    content = textContext.trim()
                }
                if (content.isNotEmpty()) {
                    return rewriteConcise(content)
                }
            }

            val friendPattern = Pattern.compile(
                "(?i)(?:^|[,\\s]+)(?:make\\s+this\\s+sound\\s+friendlier|make\\s+this\\s+friendlier|make\\s+this\\s+friendly|fă\\s+asta\\s+mai\\s+prietenoasă)[.]?$",
                Pattern.UNICODE_CHARACTER_CLASS
            )
            val mf = friendPattern.matcher(text)
            if (mf.find()) {
                var content = text.substring(0, mf.start()).trim()
                if (content.isEmpty() && !textContext.isNullOrBlank()) {
                    content = textContext.trim()
                }
                if (content.isNotEmpty()) {
                    return rewriteFriendly(content)
                }
            }

            return text
        }

        private fun rewriteProfessional(content: String): String {
            var res = content
            val replacements = mapOf(
                "(?i)\\bI am writing to tell you that\\b" to "I am writing to inform you that",
                "(?i)\\bcan't make it\\b" to "will be unable to attend",
                "(?i)\\blet me know\\b" to "please advise",
                "(?i)\\bthanks a lot\\b" to "thank you very much",
                "(?i)\\bget in touch\\b" to "reach out to discuss",
                "(?i)\\bneed this ASAP\\b" to "this matter requires urgent attention",
                "(?i)\\bwanna\\b" to "would like to",
                "(?i)\\bgonna\\b" to "intend to",
                "(?i)\\bnu mai pot veni\\b" to "vă informez cu regret că nu voi putea fi prezent",
                "(?i)\\bsă-mi zici\\b" to "vă rog să mă informați",
                "(?i)\\bsă-mi spui\\b" to "vă rog să mă informați",
                "(?i)\\bmersi\\b" to "vă mulțumesc",
                "(?i)\\bvreau să\\b" to "aș dori să",
                "(?i)\\btrebuie neapărat\\b" to "este prioritar"
            )
            for ((pat, rep) in replacements) {
                res = res.replace(pat.toRegex(), rep)
            }
            return res
        }

        private fun rewriteConcise(content: String): String {
            var res = content
            val replacements = mapOf(
                "(?i)\\bdue to the fact that\\b" to "because",
                "(?i)\\bin order to\\b" to "to",
                "(?i)\\bat this moment in time\\b" to "now",
                "(?i)\\bat this point in time\\b" to "now",
                "(?i)\\bfor the purpose of\\b" to "for",
                "(?i)\\bwith reference to\\b" to "regarding",
                "(?i)\\bîn ceea ce privește\\b" to "privind",
                "(?i)\\bavând în vedere faptul că\\b" to "deoarece",
                "(?i)\\bîn momentul de față\\b" to "acum",
                "(?i)\\bcu scopul de a\\b" to "pentru a"
            )
            for ((pat, rep) in replacements) {
                res = res.replace(pat.toRegex(), rep)
            }
            return res
        }

        private fun rewriteFriendly(content: String): String {
            var res = content
            val replacements = mapOf(
                "(?i)\\bPlease do this\\b" to "Could you please take a look at this when you have a chance?",
                "(?i)\\bSend me\\b" to "Could you send me",
                "(?i)\\bTrimite-mi\\b" to "Te rog să-mi trimiți când ai un moment"
            )
            for ((pat, rep) in replacements) {
                res = res.replace(pat.toRegex(), rep)
            }
            return res
        }

        /**
         * Strips immediate repeated words, stuttered syllables ("s-s-so"), and 2-word phrases ("am fost am fost").
         */
        fun stripRepetitions(input: String): String {
            var text = input

            // 1. Syllable stutters: s-s-so -> so, d-d-do -> do, etc.
            val syllableStutter = Pattern.compile(
                "\\b([a-zA-ZăâîșțĂÂÎȘȚа-яА-ЯёЁàâçéèêëîïôûùüÿñæœ])-+(\\1[a-zA-ZăâîșțĂÂÎȘȚа-яА-ЯёЁàâçéèêëîïôûùüÿñæœ]+)\\b",
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CHARACTER_CLASS
            )
            var mSyllable = syllableStutter.matcher(text)
            while (mSyllable.find()) {
                text = mSyllable.replaceAll("$2")
                mSyllable = syllableStutter.matcher(text)
            }

            // 2. Repeated words: "no no no" -> "no", "nu nu nu" -> "nu", "I I I" -> "I"
            val singleWordRep = Pattern.compile(
                "\\b([a-zA-ZăâîșțĂÂÎȘȚа-яА-ЯёЁàâçéèêëîïôûùüÿñæœ0-9]+)(?:\\s+\\1\\b)+",
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CHARACTER_CLASS
            )
            var m = singleWordRep.matcher(text)
            while (m.find()) {
                text = m.replaceAll("$1")
                m = singleWordRep.matcher(text)
            }

            // 3. Repeated 2-word phrases: "am fost am fost" -> "am fost"
            val twoWordRep = Pattern.compile(
                "\\b([a-zA-ZăâîșțĂÂÎȘȚа-яА-ЯёЁàâçéèêëîïôûùüÿñæœ0-9]+\\s+[a-zA-ZăâîșțĂÂÎȘȚа-яА-ЯёЁàâçéèêëîïôûùüÿñæœ0-9]+)(?:\\s+\\1\\b)+",
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CHARACTER_CLASS
            )
            var m2 = twoWordRep.matcher(text)
            while (m2.find()) {
                text = m2.replaceAll("$1")
                m2 = twoWordRep.matcher(text)
            }

            // 4. Repeated multi-word loops (e.g. ASR hallucination loops "heart emoji heart emoji...")
            val multiWordRep = Pattern.compile(
                "\\b([a-zA-ZăâîșțĂÂÎȘȚа-яА-ЯёЁàâçéèêëîïôûùüÿñæœ0-9]+(?:\\s+[a-zA-ZăâîșțĂÂÎȘȚа-яА-ЯёЁàâçéèêëîïôûùüÿñæœ0-9]+){0,3})(?:\\s+\\1){2,}\\b",
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CHARACTER_CLASS
            )
            var m3 = multiWordRep.matcher(text)
            while (m3.find()) {
                text = m3.replaceAll("$1")
                m3 = multiWordRep.matcher(text)
            }
            return text
        }
    }

    fun parseEditCommand(input: String): Pair<String, String>? = Companion.parseEditCommand(input)

    override suspend fun rewrite(
        rawText: String,
        stripDisfluencies: Boolean,
        restoreDiacritics: Boolean,
        smartPunctuation: Boolean,
        preserveLoanwords: Boolean,
        aiMode: Boolean,
        textContext: String?
    ): String = withContext(Dispatchers.Default) {
        if (rawText.isBlank()) return@withContext rawText

        // ─── Stage 1: Always apply fast regex-based cleanup first ───
        // This handles self-corrections, emoji commands, rewrite commands,
        // disfluencies, repetitions, diacritics, tech words, and punctuation.
        // Even if Qwen is available, this pre-processing reduces the workload.

        // 1. Process thought corrections / false starts first
        var processed = handleSelfCorrections(rawText)

        // 2. Process emoji commands
        processed = handleEmojiCommands(processed)

        // 3. Process conversational rewrite instructions
        processed = handleRewriteCommands(processed, textContext)

        // 4. Fast restoration (disfluencies, repetitions, diacritics, tech words, punctuation)
        val regexResult = fastRestore(
            rawText = processed,
            stripDisfluencies = stripDisfluencies,
            restoreDiacritics = restoreDiacritics,
            smartPunctuation = smartPunctuation,
            preserveLoanwords = preserveLoanwords
        )

        // ─── Stage 2: If AI mode is enabled and Qwen is available, refine with LLM ───
        val rewriter = qwenRewriter
        if (aiMode && rewriter != null && rewriter.isReady()) {
            try {
                val prompt = buildPrompt(rawText)
                val qwenResult = rewriter.rewrite(prompt)
                if (qwenResult.isNotBlank() && qwenResult.length >= rawText.length / 4) {
                    // Qwen result looks valid — use it
                    // Apply basic capitalization + terminal punctuation to Qwen output
                    var finalResult = qwenResult.trim()
                    if (smartPunctuation) {
                        finalResult = capitalizeSentences(finalResult)
                    }
                    return@withContext finalResult
                }
            } catch (e: Throwable) {
                // Qwen failed — fall back to regex result silently
                android.util.Log.e("RamblerEngine", "Qwen rewrite failed, falling back to regex", e)
            }
        }

        regexResult
    }

    fun fastRestore(
        rawText: String,
        stripDisfluencies: Boolean = true,
        restoreDiacritics: Boolean = true,
        smartPunctuation: Boolean = true,
        preserveLoanwords: Boolean = true
    ): String {
        var text = rawText.trim()

        // 1. Strip disfluencies and filler words if enabled
        if (stripDisfluencies) {
            val matcher = FILLERS_PATTERN.matcher(text)
            text = matcher.replaceAll("")
            val likeMatcher = LIKE_FILLER_PATTERN.matcher(text)
            text = likeMatcher.replaceAll(" ")
            text = stripRepetitions(text)
        }

        // 2. Normalize multiple spaces and dangling commas
        text = text.replace("\\s*([,;])\\s*".toRegex(), "$1 ")
        text = text.replace("^[\\s,;]+".toRegex(), "")
        text = text.replace("\\s+".toRegex(), " ").trim()
        if (text.isEmpty()) return ""

        // 3. Preserve tech loanwords at code-switching boundaries
        if (preserveLoanwords) {
            for ((garbled, proper) in TECH_LOANWORDS) {
                val pattern = "\\b" + Pattern.quote(garbled) + "\\b"
                text = text.replace(pattern.toRegex(RegexOption.IGNORE_CASE), proper)
            }
        }

        // 4. Restore common Romanian diacritics on Latin tokens
        if (restoreDiacritics) {
            val tokens = text.split(" ")
            val restoredTokens = tokens.map { token ->
                val clean = token.trimEnd('.', ',', '!', '?', ';', ':', '…')
                val punctuation = token.substring(clean.length)
                val replacement = ROMANIAN_DIACRITICS[clean] ?: clean
                replacement + punctuation
            }
            text = restoredTokens.joinToString(" ")
        }

        // 5. Smart capitalization
        if (smartPunctuation) {
            text = capitalizeSentences(text)
            // Capitalize standalone English "I"
            text = text.replace("\\b(i)\\b".toRegex(), "I")

            // 6. Terminal punctuation inference (only if sentence doesn't end with punctuation or emoji)
            val hasTerminal = text.endsWith(".") || text.endsWith("!") || text.endsWith("?") || text.endsWith("…")
            val endsWithEmoji = text.isNotEmpty() && Character.isSurrogate(text.last())
            if (!hasTerminal && !endsWithEmoji) {
                val firstWord = text.split(" ").firstOrNull()?.lowercase()?.trimEnd(',', ';') ?: ""
                if (firstWord in QUESTION_WORDS) {
                    text += "?"
                } else {
                    text += "."
                }
            }
        }

        return text
    }

    private fun capitalizeSentences(input: String): String {
        val sb = StringBuilder(input.length)
        var capitalizeNext = true
        for (i in input.indices) {
            val c = input[i]
            if (capitalizeNext && Character.isLetter(c)) {
                sb.append(Character.toUpperCase(c))
                capitalizeNext = false
            } else {
                sb.append(c)
                if (c == '.' || c == '!' || c == '?') {
                    capitalizeNext = true
                }
            }
        }
        return sb.toString()
    }
}

class RamblerTextController(
    private val inputConnection: InputConnection?
) {
    private val transcriptBuffer = StringBuilder()

    fun onPartialTokenReceived(token: String) {
        if (inputConnection == null) return
        transcriptBuffer.append(token)
        inputConnection.setComposingText(transcriptBuffer.toString(), 1)
    }

    fun updateTranscript(fullDraft: String) {
        if (inputConnection == null) return
        transcriptBuffer.clear()
        transcriptBuffer.append(fullDraft)
        inputConnection.setComposingText(transcriptBuffer.toString(), 1)
    }

    fun onSpeechComplete(
        slmEngine: LlamaEngineRunner,
        stripDisfluencies: Boolean = true,
        restoreDiacritics: Boolean = true,
        smartPunctuation: Boolean = true,
        onComplete: ((String) -> Unit)? = null
    ) {
        val rawText = transcriptBuffer.toString()
        if (rawText.isBlank() || inputConnection == null) {
            onComplete?.invoke(rawText)
            return
        }

        inputConnection.finishComposingText()

        CoroutineScope(Dispatchers.Default).launch {
            val cleanedText = slmEngine.rewrite(
                rawText = rawText,
                stripDisfluencies = stripDisfluencies,
                restoreDiacritics = restoreDiacritics,
                smartPunctuation = smartPunctuation
            )

            withContext(Dispatchers.Main) {
                inputConnection.beginBatchEdit()
                inputConnection.deleteSurroundingText(rawText.length, 0)
                inputConnection.commitText(cleanedText, 1)
                inputConnection.endBatchEdit()

                transcriptBuffer.clear()
                onComplete?.invoke(cleanedText)
            }
        }
    }
}
