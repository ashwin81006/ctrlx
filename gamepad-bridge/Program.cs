using System;
using System.Collections.Generic;
using System.Globalization;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;

namespace Ctrlx.GamepadBridge
{
    class Program
    {
        private static ViGEmClient? _client;
        private static readonly Dictionary<int, IXbox360Controller> _controllers = new();
        private static readonly object _syncLock = new object();

        private static readonly Dictionary<string, Xbox360Button> _buttonMap = new(StringComparer.OrdinalIgnoreCase)
        {
            { "A", Xbox360Button.A },
            { "B", Xbox360Button.B },
            { "X", Xbox360Button.X },
            { "Y", Xbox360Button.Y },
            { "LB", Xbox360Button.LeftShoulder },
            { "RB", Xbox360Button.RightShoulder },
            { "LEFTSHOULDER", Xbox360Button.LeftShoulder },
            { "RIGHTSHOULDER", Xbox360Button.RightShoulder },
            { "L3", Xbox360Button.LeftThumb },
            { "R3", Xbox360Button.RightThumb },
            { "LS", Xbox360Button.LeftThumb },
            { "RS", Xbox360Button.RightThumb },
            { "START", Xbox360Button.Start },
            { "MENU", Xbox360Button.Start },
            { "BACK", Xbox360Button.Back },
            { "VIEW", Xbox360Button.Back },
            { "SELECT", Xbox360Button.Back },
            { "XBOX", Xbox360Button.Guide },
            { "GUIDE", Xbox360Button.Guide },
            { "UP", Xbox360Button.Up },
            { "DPAD_UP", Xbox360Button.Up },
            { "DOWN", Xbox360Button.Down },
            { "DPAD_DOWN", Xbox360Button.Down },
            { "LEFT", Xbox360Button.Left },
            { "DPAD_LEFT", Xbox360Button.Left },
            { "RIGHT", Xbox360Button.Right },
            { "DPAD_RIGHT", Xbox360Button.Right },
        };

        private static readonly Xbox360Button[] _allButtons = new[]
        {
            Xbox360Button.A, Xbox360Button.B, Xbox360Button.X, Xbox360Button.Y,
            Xbox360Button.LeftShoulder, Xbox360Button.RightShoulder,
            Xbox360Button.LeftThumb, Xbox360Button.RightThumb,
            Xbox360Button.Start, Xbox360Button.Back, Xbox360Button.Guide,
            Xbox360Button.Up, Xbox360Button.Down, Xbox360Button.Left, Xbox360Button.Right
        };

        private static int _cleanedUp = 0;

        static void Main(string[] args)
        {
            AppDomain.CurrentDomain.ProcessExit += (s, e) => Cleanup();
            Console.CancelKeyPress += (s, e) => Cleanup();

            try
            {
                _client = new ViGEmClient();
                Console.WriteLine("READY: VIRTUAL_XBOX360_CONNECTED");
                Console.Out.Flush();
            }
            catch (Exception ex)
            {
                Console.WriteLine("ERROR: FAILED_TO_CONNECT_VIGEM: " + ex.Message);
                Console.Out.Flush();
                return;
            }

            string? line;
            while ((line = Console.ReadLine()) != null)
            {
                line = line.Trim();
                if (line.Length == 0) continue;

                if (line.Equals("EXIT", StringComparison.OrdinalIgnoreCase) ||
                    line.Equals("QUIT", StringComparison.OrdinalIgnoreCase))
                {
                    break;
                }

                try
                {
                    ProcessCommand(line);
                }
                catch (Exception ex)
                {
                    Console.Error.WriteLine("Error processing line: " + ex.Message);
                }
            }

            Cleanup();
        }

        private static IXbox360Controller? GetOrCreateController(int slot)
        {
            if (_client == null) return null;
            if (_controllers.TryGetValue(slot, out var existing))
            {
                return existing;
            }

            try
            {
                var controller = _client.CreateXbox360Controller();
                controller.Connect();
                ResetController(controller);
                _controllers[slot] = controller;
                Console.WriteLine($"STATUS: CONNECTED SLOT {slot}");
                Console.Out.Flush();
                return controller;
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine($"Failed to connect controller slot {slot}: {ex.Message}");
                return null;
            }
        }

        private static void DisconnectController(int slot)
        {
            if (_controllers.TryGetValue(slot, out var controller))
            {
                try
                {
                    ResetController(controller);
                    controller.Disconnect();
                }
                catch { }
                _controllers.Remove(slot);
                Console.WriteLine($"STATUS: DISCONNECTED SLOT {slot}");
                Console.Out.Flush();
            }
        }

        private static void ProcessCommand(string line)
        {
            string[] parts = line.Split(' ', StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length == 0) return;

            string cmd = parts[0].ToUpperInvariant();

            lock (_syncLock)
            {
                switch (cmd)
                {
                    case "CONNECT":
                        {
                            int slot = 1;
                            if (parts.Length >= 2 && int.TryParse(parts[1], out int s))
                            {
                                slot = s;
                            }
                            GetOrCreateController(slot);
                        }
                        break;

                    case "DISCONNECT":
                        {
                            if (parts.Length >= 2 && parts[1].Equals("ALL", StringComparison.OrdinalIgnoreCase))
                            {
                                var slots = new List<int>(_controllers.Keys);
                                foreach (var sl in slots)
                                {
                                    DisconnectController(sl);
                                }
                            }
                            else
                            {
                                int slot = 1;
                                if (parts.Length >= 2 && int.TryParse(parts[1], out int s))
                                {
                                    slot = s;
                                }
                                DisconnectController(slot);
                            }
                        }
                        break;

                    case "BTN":
                        // Formats:
                        // BTN <slot> <name> <1|0|DOWN|UP>
                        // BTN <name> <1|0|DOWN|UP> (defaults to slot 1)
                        if (parts.Length >= 3)
                        {
                            int slot = 1;
                            string btnName;
                            string stateStr;

                            if (int.TryParse(parts[1], out int s) && parts.Length >= 4)
                            {
                                slot = s;
                                btnName = parts[2];
                                stateStr = parts[3];
                            }
                            else
                            {
                                btnName = parts[1];
                                stateStr = parts[2];
                            }

                            var controller = GetOrCreateController(slot);
                            if (controller == null) break;

                            bool isDown = stateStr == "1" || stateStr.Equals("DOWN", StringComparison.OrdinalIgnoreCase);

                            if (btnName.Equals("LT", StringComparison.OrdinalIgnoreCase))
                            {
                                controller.SetSliderValue(Xbox360Slider.LeftTrigger, (byte)(isDown ? 255 : 0));
                            }
                            else if (btnName.Equals("RT", StringComparison.OrdinalIgnoreCase))
                            {
                                controller.SetSliderValue(Xbox360Slider.RightTrigger, (byte)(isDown ? 255 : 0));
                            }
                            else if (_buttonMap.TryGetValue(btnName, out var xboxBtn))
                            {
                                controller.SetButtonState(xboxBtn, isDown);
                            }
                        }
                        break;

                    case "AXIS":
                        // Formats:
                        // AXIS <slot> <LEFT|RIGHT> <x> <y>
                        // AXIS <LEFT|RIGHT> <x> <y> (defaults to slot 1)
                        if (parts.Length >= 4)
                        {
                            int slot = 1;
                            string stick;
                            string strX;
                            string strY;

                            if (int.TryParse(parts[1], out int s) && parts.Length >= 5)
                            {
                                slot = s;
                                stick = parts[2].ToUpperInvariant();
                                strX = parts[3];
                                strY = parts[4];
                            }
                            else
                            {
                                stick = parts[1].ToUpperInvariant();
                                strX = parts[2];
                                strY = parts[3];
                            }

                            var controller = GetOrCreateController(slot);
                            if (controller == null) break;

                            if (float.TryParse(strX, NumberStyles.Float, CultureInfo.InvariantCulture, out float x) &&
                                float.TryParse(strY, NumberStyles.Float, CultureInfo.InvariantCulture, out float y))
                            {
                                short shortX = (short)Math.Clamp(Math.Round(x * 32767f), -32768, 32767);
                                // Mobile joystick has +y down, -y up. Invert Y so up is positive in XInput.
                                short shortY = (short)Math.Clamp(Math.Round(-y * 32767f), -32768, 32767);

                                if (stick == "LEFT" || stick == "LS")
                                {
                                    controller.SetAxisValue(Xbox360Axis.LeftThumbX, shortX);
                                    controller.SetAxisValue(Xbox360Axis.LeftThumbY, shortY);
                                }
                                else if (stick == "RIGHT" || stick == "RS")
                                {
                                    controller.SetAxisValue(Xbox360Axis.RightThumbX, shortX);
                                    controller.SetAxisValue(Xbox360Axis.RightThumbY, shortY);
                                }
                            }
                        }
                        break;

                    case "TRIGGER":
                        // Formats:
                        // TRIGGER <slot> <LT|RT> <value 0.0-1.0>
                        // TRIGGER <LT|RT> <value 0.0-1.0> (defaults to slot 1)
                        if (parts.Length >= 3)
                        {
                            int slot = 1;
                            string trigger;
                            string strVal;

                            if (int.TryParse(parts[1], out int s) && parts.Length >= 4)
                            {
                                slot = s;
                                trigger = parts[2].ToUpperInvariant();
                                strVal = parts[3];
                            }
                            else
                            {
                                trigger = parts[1].ToUpperInvariant();
                                strVal = parts[2];
                            }

                            var controller = GetOrCreateController(slot);
                            if (controller == null) break;

                            if (float.TryParse(strVal, NumberStyles.Float, CultureInfo.InvariantCulture, out float val))
                            {
                                byte byteVal = (byte)Math.Clamp(Math.Round(val * 255f), 0, 255);
                                if (trigger == "LT" || trigger == "LEFT")
                                {
                                    controller.SetSliderValue(Xbox360Slider.LeftTrigger, byteVal);
                                }
                                else if (trigger == "RT" || trigger == "RIGHT")
                                {
                                    controller.SetSliderValue(Xbox360Slider.RightTrigger, byteVal);
                                }
                            }
                        }
                        break;

                    case "RESET":
                        if (parts.Length >= 2)
                        {
                            if (parts[1].Equals("ALL", StringComparison.OrdinalIgnoreCase))
                            {
                                foreach (var c in _controllers.Values)
                                {
                                    ResetController(c);
                                }
                            }
                            else if (int.TryParse(parts[1], out int slot))
                            {
                                if (_controllers.TryGetValue(slot, out var c))
                                {
                                    ResetController(c);
                                }
                            }
                        }
                        else
                        {
                            foreach (var c in _controllers.Values)
                            {
                                ResetController(c);
                            }
                        }
                        break;

                    case "PING":
                        Console.WriteLine("PONG");
                        Console.Out.Flush();
                        break;

                    case "EXIT":
                    case "QUIT":
                        break;
                }
            }
        }

        private static void ResetController(IXbox360Controller? controller)
        {
            if (controller == null) return;

            foreach (var btn in _allButtons)
            {
                try
                {
                    controller.SetButtonState(btn, false);
                }
                catch { }
            }

            try
            {
                controller.SetAxisValue(Xbox360Axis.LeftThumbX, 0);
                controller.SetAxisValue(Xbox360Axis.LeftThumbY, 0);
                controller.SetAxisValue(Xbox360Axis.RightThumbX, 0);
                controller.SetAxisValue(Xbox360Axis.RightThumbY, 0);

                controller.SetSliderValue(Xbox360Slider.LeftTrigger, 0);
                controller.SetSliderValue(Xbox360Slider.RightTrigger, 0);
            }
            catch { }
        }

        private static void Cleanup()
        {
            if (System.Threading.Interlocked.Exchange(ref _cleanedUp, 1) != 0)
            {
                return;
            }

            lock (_syncLock)
            {
                foreach (var kvp in _controllers)
                {
                    try
                    {
                        ResetController(kvp.Value);
                        kvp.Value.Disconnect();
                    }
                    catch { }
                }
                _controllers.Clear();

                if (_client != null)
                {
                    try
                    {
                        _client.Dispose();
                    }
                    catch { }
                    _client = null;
                }
            }
        }
    }
}
