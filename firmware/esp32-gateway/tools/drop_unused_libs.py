# PlatformIO extra script (post): links the gateway without two Arduino libraries it does not use.
#
# The precompiled Arduino link line lists esp_matter and esp_modem before libstdc++. Both are C++ libraries whose
# objects carry their own copies of the std::string template code: the linker then took some std::string functions the
# gateway calls from Matter objects, dragging in Matter's globals (about 13 KB of internal RAM, constructed at boot) and
# part of esp_modem. Without them on the link line these functions come from libstdc++ as intended.
Import("env")  # noqa: F821  (SCons global)

UNUSED = {"-lespressif__esp_matter", "-lespressif__esp_modem"}

env.Replace(LIBS=[lib for lib in env.get("LIBS", []) if str(lib) not in UNUSED])  # noqa: F821
