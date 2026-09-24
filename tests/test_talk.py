import os
import unittest

from xen.talk.voice import MODEL_DIR, MODEL_NAME, carrying, honest, notes, plainly, safe_chat

MODEL = os.environ.get("XEN_MODEL") or os.path.join(MODEL_DIR, MODEL_NAME)


class TestChatRules(unittest.TestCase):
    def test_replies_can_never_be_commands(self):
        self.assertEqual(safe_chat("/give @s diamond 64"), "give @s diamond 64")
        self.assertEqual(safe_chat("  //op Xen"), "op Xen")
        self.assertNotIn("\n", safe_chat("line one\n/kill @a"))
        self.assertEqual(safe_chat("One. Two. Three. Four."), "One. Two.")
        self.assertLessEqual(len(safe_chat("word " * 200)), 224)
        self.assertEqual(safe_chat("<|im_end|>"), "...")

    def test_it_cannot_claim_what_it_has_not_seen(self):
        seen = notes("calm", False, 20, 20, "", "You saw a tree about 12 blocks away.")
        self.assertEqual(honest("I see a tree nearby.", seen), "I see a tree nearby.")
        self.assertEqual(honest("There's a diamond right here! Let's go.", seen), "Let's go.")
        self.assertEqual(honest("I haven't seen any diamonds.", seen), "I haven't seen any diamonds.")
        self.assertEqual(honest("I found diamonds nearby.", seen), "I'm not sure, I haven't seen that.")
        self.assertEqual(honest("It's a nice environment here. I might have seen a wolf over there.", seen),
                         "It's a nice environment here.")
        # Being asked about diamonds doesn't let it claim there are some.
        self.assertEqual(honest("Yes, I know there's a diamond nearby. Want it?", seen), "Want it?")

    def test_it_can_always_just_say_what_it_knows(self):
        n = notes("afraid", True, 5, 20, "3 dirt", "You know there is lava 3 blocks from you. You think there was "
                  "iron ore about 40 blocks away, but you're not sure.")
        self.assertEqual(plainly(n), "I feel afraid. I'm hurt.")
        n = notes("calm", False, 20, 20, "3 dirt", "You think there was iron ore about 40 blocks away, but you're not sure.")
        self.assertEqual(plainly(n), "I feel calm. I think there was iron ore about 40 blocks away, but I'm not sure.")
        self.assertEqual(plainly(n, "any diamonds?"), "I haven't seen any diamonds. I think there was iron ore about 40 "
                                                      "blocks away, but I'm not sure.")

    def test_notes_are_plain_words(self):
        n = notes("afraid", True, 5, 3, carrying({"dirt": 5, "coal": 0, "log": 2}), "You saw lava about 9 blocks away.")
        self.assertIn("You feel afraid.", n)
        self.assertIn("You are hurt.", n)
        self.assertIn("You are hungry.", n)
        self.assertIn("5 dirt, 2 log", n)
        self.assertNotIn("0 coal", n)


@unittest.skipUnless(os.path.exists(MODEL), "voice model not downloaded (python -m xen talk fetches it)")
class TestLanguageModel(unittest.TestCase):
    def test_tokenizer_and_a_short_answer(self):
        from xen.talk.llm import LanguageModel
        lm = LanguageModel(MODEL)
        ids = lm.tokenizer.encode("I found 12 diamonds at y=-58.")
        self.assertEqual(lm.tokenizer.decode(ids), "I found 12 diamonds at y=-58.")
        out = lm.generate("<|im_start|>user\nWhat is the capital of France?<|im_end|>\n<|im_start|>assistant\n",
                          max_tokens=8, temperature=0)
        self.assertIn("Paris", out)


if __name__ == "__main__":
    unittest.main()
