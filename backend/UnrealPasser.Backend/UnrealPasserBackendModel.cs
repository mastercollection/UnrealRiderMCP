#nullable disable

using System;
using System.Collections.Generic;
using JetBrains.Rd;
using JetBrains.Rd.Base;
using JetBrains.Rd.Impl;
using JetBrains.Rd.Tasks;
using JetBrains.Rider.Model;

namespace UnrealPasser.Backend;

public sealed class UnrealPasserBackendModel : RdExtBase
{
    public const string ExtensionName = "UnrealPasserBackendModel";
    public const long ModelSerializationHash = 0x5EA2000000000001L;

    private readonly RdCall<string, string> myExecute;

    public UnrealPasserBackendModel()
        : this(new RdCall<string, string>(
            Serializers.ReadString,
            Serializers.WriteString,
            Serializers.ReadString,
            Serializers.WriteString))
    {
    }

    private UnrealPasserBackendModel(RdCall<string, string> execute)
    {
        myExecute = execute ?? throw new ArgumentNullException(nameof(execute));
        BindableChildren.Add(new KeyValuePair<string, object>("execute", myExecute));
    }

    public IRdEndpoint<string, string> Execute => myExecute;

    protected override long SerializationHash => ModelSerializationHash;

    protected override Action<ISerializers> Register => RegisterDeclaredTypesSerializers;

    private static void RegisterDeclaredTypesSerializers(ISerializers serializers)
    {
    }
}

public static class SolutionUnrealPasserBackendModelEx
{
    public static UnrealPasserBackendModel GetUnrealPasserBackendModel(this Solution solution)
    {
        return solution.GetOrCreateExtension(
            UnrealPasserBackendModel.ExtensionName,
            static () => new UnrealPasserBackendModel());
    }
}
