- **Held arguments: three more contract cases.** `contracts/held-arguments.json` now pins:
  - long decimals and integers with every digit as sent;
  - a number whose plain form would pass 100 digits, written as sent (`1e400`, `-1E+400`), and a zero written `0` whatever its exponent;
  - arguments nested 32 levels, the deepest KIFF holds.

  Every app reads the number's own text, never through a double.
