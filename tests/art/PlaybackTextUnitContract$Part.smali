.class public final Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;
.super Ljava/lang/Object;
.source "SourceFile"


# annotations
.annotation system Ldalvik/annotation/EnclosingClass;
    value = Lio/legado/app/help/audiobook/PlaybackTextUnitContract;
.end annotation

.annotation system Ldalvik/annotation/InnerClass;
    accessFlags = 0x19
    name = "Part"
.end annotation


# static fields
.field public static final $stable:I


# instance fields
.field private final rawEnd:I

.field private final rawStart:I

.field private final text:Ljava/lang/String;


# direct methods
.method static constructor <clinit>()V
    .locals 0

    return-void
.end method

.method public constructor <init>(IILjava/lang/String;)V
    .locals 0

    invoke-virtual {p3}, Ljava/lang/Object;->getClass()Ljava/lang/Class;

    .line 17
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    .line 18
    iput p1, p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->rawStart:I

    .line 19
    iput p2, p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->rawEnd:I

    .line 20
    iput-object p3, p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->text:Ljava/lang/String;

    return-void
.end method

.method public static synthetic copy$default(Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;IILjava/lang/String;ILjava/lang/Object;)Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;
    .locals 0

    and-int/lit8 p5, p4, 0x1

    if-eqz p5, :cond_0

    iget p1, p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->rawStart:I

    :cond_0
    and-int/lit8 p5, p4, 0x2

    if-eqz p5, :cond_1

    iget p2, p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->rawEnd:I

    :cond_1
    and-int/lit8 p4, p4, 0x4

    if-eqz p4, :cond_2

    iget-object p3, p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->text:Ljava/lang/String;

    :cond_2
    invoke-virtual {p0, p1, p2, p3}, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->copy(IILjava/lang/String;)Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;

    move-result-object p0

    return-object p0
.end method


# virtual methods
.method public final component1()I
    .locals 0

    iget p0, p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->rawStart:I

    return p0
.end method

.method public final component2()I
    .locals 0

    iget p0, p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->rawEnd:I

    return p0
.end method

.method public final component3()Ljava/lang/String;
    .locals 0

    iget-object p0, p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->text:Ljava/lang/String;

    return-object p0
.end method

.method public final copy(IILjava/lang/String;)Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;
    .locals 0

    invoke-virtual {p3}, Ljava/lang/Object;->getClass()Ljava/lang/Class;

    new-instance p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;

    invoke-direct {p0, p1, p2, p3}, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;-><init>(IILjava/lang/String;)V

    return-object p0
.end method

.method public equals(Ljava/lang/Object;)Z
    .locals 4

    const/4 v0, 0x1

    if-ne p0, p1, :cond_0

    return v0

    :cond_0
    instance-of v1, p1, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;

    const/4 v2, 0x0

    if-nez v1, :cond_1

    return v2

    :cond_1
    check-cast p1, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;

    iget v1, p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->rawStart:I

    iget v3, p1, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->rawStart:I

    if-eq v1, v3, :cond_2

    return v2

    :cond_2
    iget v1, p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->rawEnd:I

    iget v3, p1, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->rawEnd:I

    if-eq v1, v3, :cond_3

    return v2

    :cond_3
    iget-object p0, p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->text:Ljava/lang/String;

    iget-object p1, p1, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->text:Ljava/lang/String;

    invoke-static {p0, p1}, Lkotlin/jvm/internal/Intrinsics;->areEqual(Ljava/lang/Object;Ljava/lang/Object;)Z

    move-result p0

    if-nez p0, :cond_4

    return v2

    :cond_4
    return v0
.end method

.method public final getRawEnd()I
    .locals 0

    .line 19
    iget p0, p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->rawEnd:I

    return p0
.end method

.method public final getRawStart()I
    .locals 0

    .line 18
    iget p0, p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->rawStart:I

    return p0
.end method

.method public final getText()Ljava/lang/String;
    .locals 0

    .line 20
    iget-object p0, p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->text:Ljava/lang/String;

    return-object p0
.end method

.method public hashCode()I
    .locals 2

    iget v0, p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->rawStart:I

    invoke-static {v0}, Ljava/lang/Integer;->hashCode(I)I

    move-result v0

    mul-int/lit8 v0, v0, 0x1f

    iget v1, p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->rawEnd:I

    invoke-static {v1}, Ljava/lang/Integer;->hashCode(I)I

    move-result v1

    add-int/2addr v0, v1

    mul-int/lit8 v0, v0, 0x1f

    iget-object p0, p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->text:Ljava/lang/String;

    invoke-virtual {p0}, Ljava/lang/String;->hashCode()I

    move-result p0

    add-int/2addr v0, p0

    return v0
.end method

.method public toString()Ljava/lang/String;
    .locals 4

    iget v0, p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->rawStart:I

    iget v1, p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->rawEnd:I

    iget-object p0, p0, Lio/legado/app/help/audiobook/PlaybackTextUnitContract$Part;->text:Ljava/lang/String;

    new-instance v2, Ljava/lang/StringBuilder;

    const-string v3, "Part(rawStart="

    invoke-direct {v2, v3}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v2, v0}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    const-string v0, ", rawEnd="

    invoke-virtual {v2, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v2, v1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    const-string v0, ", text="

    invoke-virtual {v2, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v2, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string p0, ")"

    invoke-virtual {v2, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    return-object p0
.end method
