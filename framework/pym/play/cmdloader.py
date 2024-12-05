from __future__ import print_function
import os
import warnings
import traceback

def play_formatwarning(msg, *a):
    # ignore everything except the message
    # format the message in a play cmdline way
    return '~'+ '\n'+ '~ '+ str(msg) + '\n~\n'

import sys

# Define constants that were previously in the imp module
PY_SOURCE = 1
PY_COMPILED = 2
C_EXTENSION = 3

if sys.version_info >= (3, 12):
    # Use importlib for Python 3.12 and onwards
    import importlib.util
    import importlib.machinery
else:
    # Use imp for Python versions <= 3.11
    import imp

# Wrapper for loading a module from source
def load_source_wrapper(name, pathname):
    if name in sys.modules:
        return sys.modules[name]

    if sys.version_info >= (3, 12):
        if not os.path.exists(pathname):
            raise ImportError(f"Cannot find module {name} at {pathname}")
        spec = importlib.util.spec_from_file_location(name, pathname)
        if spec is None or spec.loader is None:
            raise ImportError(f"Cannot load module {name} from {pathname}")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        sys.modules[name] = module  # Cache the module
        return module
    else:
        module = imp.load_source(name, pathname)
        sys.modules[name] = module  # Cache the module
        return module

# Wrapper for finding a module
def find_module_wrapper(name, locations):
    if sys.version_info >= (3, 12):
        for location in locations:
            search_locations = [location]
            spec = importlib.machinery.PathFinder.find_spec(name, search_locations)
            if spec is not None:
                pathname = spec.origin
                if spec.origin is None:
                    continue
                # Open the file manually
                try:
                    file = open(pathname, 'rb')
                except FileNotFoundError:
                    continue
                # Determine the description tuple
                suffix = os.path.splitext(pathname)[1]
                if suffix == '.py':
                    description = (suffix, 'r', PY_SOURCE)
                elif suffix in ('.pyc', '.pyo'):
                    description = (suffix, 'rb', PY_COMPILED)
                elif suffix in importlib.machinery.EXTENSION_SUFFIXES:
                    description = (suffix, 'rb', C_EXTENSION)
                else:
                    file.close()
                    continue  # Unsupported file type
                return (file, pathname, description)
        raise ImportError(f"Can't find module {name} in {locations}")
    else:
        return imp.find_module(name, locations)

# Wrapper for loading a module using find_module results
def load_module_wrapper(name, file, pathname, description):
    if name in sys.modules:
        if file:
            file.close()
        return sys.modules[name]

    if sys.version_info >= (3, 12):
        suffix, mode, type_ = description
        if file is None:
            raise ImportError(f"File object is required for loading module {name}")

        # Read the source code from the file
        source = file.read()
        file.close()

        # Create a module spec
        spec = importlib.util.spec_from_loader(name, loader=None, origin=pathname)
        module = importlib.util.module_from_spec(spec)
        # Execute the module's code within its own namespace
        exec(source, module.__dict__)
        sys.modules[name] = module  # Cache the module
        return module
    else:
        module = imp.load_module(name, file, pathname, description)
        sys.modules[name] = module  # Cache the module
        return module


warnings.formatwarning = play_formatwarning

class CommandLoader(object):
    def __init__(self, play_path):
        self.path = os.path.join(play_path, 'framework', 'pym', 'play', 'commands')
        self.commands = {}
        self.modules = {}
        self.load_core()

    def load_core(self):
        for filename in os.listdir(self.path):
            if filename != "__init__.py" and filename.endswith(".py"):
                try:
                    name = filename.replace(".py", "")
                    mod = load_python_module(name, self.path)
                    self._load_cmd_from(mod)
                except Exception as e:
                    print (e)
                    traceback.print_exc()
                    warnings.warn("!! Warning: could not load core command file " + filename, RuntimeWarning)

    def load_play_module(self, modname):
        commands = os.path.join(modname, "commands.py")
        if os.path.exists(commands):
            try:
                leafname = os.path.basename(modname).split('.')[0]
                mod = load_source_wrapper(leafname, os.path.join(modname, "commands.py"))
                self._load_cmd_from(mod)
            except Exception as e:
                print('~')
                print('~ !! Error while loading %s: %s' % (commands, e))
                print('~')
                pass # No command to load in this module

    def _load_cmd_from(self, mod):
        if 'COMMANDS' in dir(mod):
            for name in mod.COMMANDS:
                try:
                    if name in self.commands:
                        warnings.warn("Warning: conflict on command " + name)
                    self.commands[name] = mod
                except Exception:
                    warnings.warn("Warning: error loading command " + name)
        if 'MODULE' in dir(mod):
            self.modules[mod.MODULE] = mod

def load_python_module(name, location):
    mod_desc = find_module_wrapper(name, [location])
    mod_file = mod_desc[0]
    try:
        return load_module_wrapper(name, mod_desc[0], mod_desc[1], mod_desc[2])
    finally:
        if mod_file != None and not mod_file.closed:
            mod_file.close()

