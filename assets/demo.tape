# VHS tape — records the AI-vs-AI demo battle into assets/demo.gif.
# Regenerate with:  vhs assets/demo.tape
# Requires a warm Gradle daemon (run `./gradlew :cli:assemble` first) so the
# recording captures battle output, not build spin-up.

Output assets/demo.gif

Set Shell "bash"
Set FontSize 18
Set Width 1000
Set Height 760
Set Padding 24
Set TypingSpeed 60ms
Set PlaybackSpeed 1.0

Type "./gradlew :cli:demo -q --console=plain"
Sleep 600ms
Enter
Sleep 5s
