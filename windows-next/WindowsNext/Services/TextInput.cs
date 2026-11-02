using System.Text;
namespace WoZai.Next.Services;
public static class TextInput
{
    private static readonly UTF8Encoding StrictUtf8 = new(false,true);
    public static int Bytes(string text) { try{return StrictUtf8.GetByteCount(text);}catch(EncoderFallbackException){return -1;} }
    public static bool CanSend(string text,int limit) {int count=Bytes(text);return !string.IsNullOrWhiteSpace(text)&&count>=0&&count<=limit;}
}
