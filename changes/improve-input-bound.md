- Improving a taf with long Cards or long vendor messages no longer fails with a bare 400. The improve message now fits within kiff/agents' input bound:
  1. The calls' vendor messages are shortened first.
  2. Then the oldest calls are left out.
  3. Then whole Cards are left out, the largest first. Coach is told which ones, and not to draft them again.
  - The owner's words and the taf's instructions are never cut.
- A kiff/agents refusal that tafs cloud passes on is now logged with its code and words, never with what was sent.
